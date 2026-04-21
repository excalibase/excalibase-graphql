package io.github.excalibase.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.*;

public class PostgresSchemaLoader implements SchemaLoader {

    private static final String COL_TABLE_NAME = "table_name";
    private static final String COL_COLUMN_NAME = "column_name";
    private static final String COL_DATA_TYPE = "data_type";
    private static final String COL_UDT_NAME = "udt_name";
    private static final String COL_CHARACTER_MAXIMUM_LENGTH = "character_maximum_length";
    private static final String COL_ARGS_SIGNATURE = "args_signature";
    private static final String COL_TABLE_SCHEMA = "table_schema";

    private static final Set<String> EXCLUDED_VIEWS = Set.of(
            "pg_stat_statements", "pg_stat_statements_info", "pg_buffercache"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BULK_INTROSPECTION_QUERY = """
            WITH
              cols AS (
                SELECT 'column' as kind, table_schema, table_name, column_name,
                       data_type, udt_name, character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM information_schema.columns
                WHERE table_schema = ANY(?)
                ORDER BY table_schema, table_name, ordinal_position
              ),
              pkeys AS (
                SELECT 'pk' as kind, tc.table_schema, tc.table_name, kcu.column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                    AND tc.table_schema = kcu.table_schema
                    AND tc.table_name = kcu.table_name
                WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ANY(?)
                ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
              ),
              fkeys AS (
                SELECT 'fk' as kind, n.nspname as table_schema, cl.relname as table_name, NULL as column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       c.conname as constraint_name, a.attname as from_column,
                       clf.relname as to_table, af.attname as to_column,
                       u.ord as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_constraint c
                JOIN pg_class cl ON c.conrelid = cl.oid
                JOIN pg_class clf ON c.confrelid = clf.oid
                JOIN pg_namespace n ON c.connamespace = n.oid
                CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS u(from_attnum, to_attnum, ord)
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = u.from_attnum
                JOIN pg_attribute af ON af.attrelid = c.confrelid AND af.attnum = u.to_attnum
                WHERE c.contype = 'f' AND n.nspname = ANY(?)
                ORDER BY n.nspname, c.conname, u.ord
              ),
              views AS (
                SELECT 'view' as kind, table_schema, table_name, NULL as column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM information_schema.views
                WHERE table_schema = ANY(?)
              ),
              matviews AS (
                SELECT 'matview' as kind, n.nspname as table_schema, c.relname as table_name, NULL as column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_class c
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = ANY(?) AND c.relkind = 'm'
              ),
              matview_cols AS (
                SELECT 'matview_col' as kind, n.nspname as table_schema, c.relname as table_name,
                       a.attname as column_name,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                       NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_class c
                JOIN pg_namespace n ON c.relnamespace = n.oid
                JOIN pg_attribute a ON a.attrelid = c.oid
                WHERE n.nspname = ANY(?) AND c.relkind = 'm' AND a.attnum > 0 AND NOT a.attisdropped
                ORDER BY n.nspname, c.relname, a.attnum
              ),
              enums AS (
                SELECT 'enum' as kind, n.nspname as table_schema, NULL as table_name, NULL as column_name,
                       NULL as data_type, t.typname as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, e.enumlabel as enum_label, e.enumsortorder as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_type t
                JOIN pg_enum e ON t.oid = e.enumtypid
                JOIN pg_namespace n ON t.typnamespace = n.oid
                WHERE n.nspname = ANY(?)
                ORDER BY n.nspname, t.typname, e.enumsortorder
              ),
              composites AS (
                SELECT 'composite' as kind, n.nspname as table_schema, NULL as table_name,
                       a.attname as column_name,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                       t.typname as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_type t
                JOIN pg_namespace n ON t.typnamespace = n.oid
                JOIN pg_class c ON c.oid = t.typrelid
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = ANY(?) AND t.typtype = 'c' AND c.relkind = 'c'
                ORDER BY n.nspname, t.typname, a.attnum
              ),
              procs AS (
                SELECT 'proc' as kind, n.nspname as table_schema, NULL as table_name, NULL as column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       p.proname as proc_name,
                       pg_get_function_identity_arguments(p.oid) as args_signature,
                       NULL as return_type, NULL as function_name
                FROM pg_proc p
                JOIN pg_namespace n ON p.pronamespace = n.oid
                WHERE n.nspname = ANY(?) AND p.prokind IN ('f', 'p')
              ),
              computed AS (
                SELECT 'computed' as kind, n.nspname as table_schema, c.relname as table_name,
                       NULL as column_name, NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature,
                       pg_get_function_result(p.oid) as return_type,
                       p.proname as function_name
                FROM pg_proc p
                JOIN pg_namespace n ON p.pronamespace = n.oid
                JOIN pg_type t ON p.proargtypes[0] = t.oid
                JOIN pg_class c ON t.typrelid = c.oid
                WHERE n.nspname = ANY(?) AND array_length(p.proargtypes, 1) = 1
              ),
              extensions AS (
                -- Extensions are global to the DB, not scoped to a schema.
                -- table_schema is left NULL and the dispatch broadcasts to all
                -- per-schema SchemaInfo entries so any of them can answer
                -- hasExtension() lookups.
                SELECT 'extension' as kind, NULL as table_schema, extname as table_name, extversion as column_name,
                       NULL as data_type, NULL as udt_name, NULL::int as character_maximum_length,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       NULL::bigint as ordinal, NULL as enum_label, NULL::double precision as sort_order,
                       NULL as proc_name, NULL as args_signature, NULL as return_type, NULL as function_name
                FROM pg_extension
              )
            SELECT row_to_json(x) FROM cols x
            UNION ALL SELECT row_to_json(x) FROM pkeys x
            UNION ALL SELECT row_to_json(x) FROM fkeys x
            UNION ALL SELECT row_to_json(x) FROM views x
            UNION ALL SELECT row_to_json(x) FROM matviews x
            UNION ALL SELECT row_to_json(x) FROM matview_cols x
            UNION ALL SELECT row_to_json(x) FROM enums x
            UNION ALL SELECT row_to_json(x) FROM composites x
            UNION ALL SELECT row_to_json(x) FROM procs x
            UNION ALL SELECT row_to_json(x) FROM computed x
            UNION ALL SELECT row_to_json(x) FROM extensions x
            """;

    @Override
    public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        if (schemas.isEmpty()) return;
        String[] schemaArray = schemas.toArray(new String[0]);

        // FK grouping: (schema.constraintName) → list of [fromCol, toCol] pairs + table refs
        Map<String, List<String[]>> fkColumns = new LinkedHashMap<>();
        Map<String, String[]> fkTables = new HashMap<>();

        jdbc.query(BULK_INTROSPECTION_QUERY, ps -> {
            // 10 CTEs take the schema-array param; the extensions CTE takes none.
            for (int i = 1; i <= 10; i++) {
                ps.setArray(i, ps.getConnection().createArrayOf("text", schemaArray));
            }
        }, (RowCallbackHandler) rs -> dispatchIntrospectionRow(rs.getString(1), schemaArray, perSchema, fkTables, fkColumns));

        postProcessForeignKeys(fkColumns, fkTables, perSchema);
        removeExcludedViews(perSchema);
    }

    /** Dispatch a single introspection row (one JSON blob) to the appropriate handler. */
    private void dispatchIntrospectionRow(String rowJson, String[] schemaArray,
                                          Map<String, SchemaInfo> perSchema,
                                          Map<String, String[]> fkTables,
                                          Map<String, List<String[]>> fkColumns) {
        try {
            JsonNode node = JSON.readTree(rowJson);
            String kind = node.get("kind").asText();
            String schema = node.has(COL_TABLE_SCHEMA) && !node.get(COL_TABLE_SCHEMA).isNull()
                    ? node.get(COL_TABLE_SCHEMA).asText() : null;

            if ("extension".equals(kind)) {
                handleExtension(node, schemaArray, perSchema);
                return;
            }
            if (schema == null) return;
            SchemaInfo info = perSchema.computeIfAbsent(schema, k -> new SchemaInfo());

            switch (kind) {
                case "column", "matview_col" -> handleColumnKind(kind, node, schema, info);
                case "pk" -> info.addPrimaryKey(node.get(COL_TABLE_NAME).asText(), node.get(COL_COLUMN_NAME).asText());
                case "fk" -> handleForeignKeyRow(node, schema, fkTables, fkColumns);
                case "view", "matview" -> handleViewKind(kind, node, info);
                case "enum" -> info.addEnumValue(node.get(COL_UDT_NAME).asText(), node.get("enum_label").asText());
                case "composite" -> info.addCompositeTypeField(
                        node.get(COL_UDT_NAME).asText(), node.get(COL_COLUMN_NAME).asText(),
                        node.get(COL_DATA_TYPE).asText());
                case "proc", "computed" -> handleProcOrComputed(kind, node, info);
                default -> { /* Ignore unknown introspection row kinds */ }
            }
        } catch (Exception e) {
            throw new io.github.excalibase.spi.SchemaIntrospectionException("Failed to parse introspection row", e);
        }
    }

    /** Extensions are global; broadcast to every per-schema SchemaInfo. */
    private void handleExtension(JsonNode node, String[] schemaArray, Map<String, SchemaInfo> perSchema) {
        String extName = node.get(COL_TABLE_NAME).asText();
        String extVer = node.has(COL_COLUMN_NAME) && !node.get(COL_COLUMN_NAME).isNull()
                ? node.get(COL_COLUMN_NAME).asText() : null;
        for (String schema : schemaArray) {
            perSchema.computeIfAbsent(schema, k -> new SchemaInfo()).addExtension(extName, extVer);
        }
    }

    /** Handles both 'column' (regular table column) and 'matview_col' (materialized view column). */
    private void handleColumnKind(String kind, JsonNode node, String schema, SchemaInfo info) {
        String table = node.get(COL_TABLE_NAME).asText();
        String col = node.get(COL_COLUMN_NAME).asText();
        String type = node.get(COL_DATA_TYPE).asText();
        String udtName = node.has(COL_UDT_NAME) && !node.get(COL_UDT_NAME).isNull()
                ? node.get(COL_UDT_NAME).asText() : null;
        Integer charMaxLen = node.has(COL_CHARACTER_MAXIMUM_LENGTH) && !node.get(COL_CHARACTER_MAXIMUM_LENGTH).isNull()
                ? node.get(COL_CHARACTER_MAXIMUM_LENGTH).asInt() : null;

        // The plain column kind performs USER-DEFINED / ARRAY / bit length rewrites,
        // while matview columns use pg_catalog.format_type and need no rewriting.
        if ("column".equals(kind)) {
            type = normalizeColumnType(type, udtName, charMaxLen, table, col, info);
        }
        info.addColumn(table, col, type);
        info.setTableSchema(table, schema);
    }

    /** Normalizes raw pg type names (USER-DEFINED, ARRAY, bit, bit varying) to their canonical forms. */
    private String normalizeColumnType(String type, String udtName, Integer charMaxLen,
                                       String table, String col, SchemaInfo info) {
        if ("USER-DEFINED".equals(type) && udtName != null) {
            info.addColumnEnumType(table, col, udtName);
            return udtName;
        }
        if ("ARRAY".equals(type) && udtName != null) {
            return udtName;
        }
        if ("bit".equals(type) && charMaxLen != null) {
            return "bit(" + charMaxLen + ")";
        }
        if ("bit varying".equals(type) && charMaxLen != null) {
            return "bit varying(" + charMaxLen + ")";
        }
        return type;
    }

    private void handleForeignKeyRow(JsonNode node, String schema,
                                     Map<String, String[]> fkTables,
                                     Map<String, List<String[]>> fkColumns) {
        String constraintKey = schema + "." + node.get("constraint_name").asText();
        String fromTable = node.get(COL_TABLE_NAME).asText();
        String fromCol = node.get("from_column").asText();
        String toTable = node.get("to_table").asText();
        String toCol = node.get("to_column").asText();
        fkTables.putIfAbsent(constraintKey, new String[]{fromTable, toTable, schema});
        fkColumns.computeIfAbsent(constraintKey, k -> new ArrayList<>())
                .add(new String[]{fromCol, toCol});
    }

    /** Handles 'view' (filters excluded extension views) and 'matview' (always added). */
    private void handleViewKind(String kind, JsonNode node, SchemaInfo info) {
        String viewName = node.get(COL_TABLE_NAME).asText();
        if ("view".equals(kind) && EXCLUDED_VIEWS.contains(viewName)) return;
        info.addView(viewName);
    }

    /** Handles 'proc' (stored procedure) and 'computed' (table-input computed function). */
    private void handleProcOrComputed(String kind, JsonNode node, SchemaInfo info) {
        if ("proc".equals(kind)) {
            String procName = node.get("proc_name").asText();
            String argsSig = node.has(COL_ARGS_SIGNATURE) && !node.get(COL_ARGS_SIGNATURE).isNull()
                    ? node.get(COL_ARGS_SIGNATURE).asText() : "";
            info.addStoredProcedure(procName, new SchemaInfo.ProcedureInfo(procName, parseProcArgs(argsSig)));
        } else {
            info.addComputedField(node.get(COL_TABLE_NAME).asText(),
                    node.get("function_name").asText(), node.get("return_type").asText());
        }
    }

    private void postProcessForeignKeys(Map<String, List<String[]>> fkColumns,
                                        Map<String, String[]> fkTables,
                                        Map<String, SchemaInfo> perSchema) {
        for (var entry : fkColumns.entrySet()) {
            String[] tables = fkTables.get(entry.getKey());
            String fromTable = tables[0];
            String toTable = tables[1];
            String fkSchema = tables[2];
            SchemaInfo info = perSchema.get(fkSchema);
            if (info == null) continue;
            List<String[]> colPairs = entry.getValue();
            if (colPairs.size() == 1) {
                info.addForeignKey(fromTable, colPairs.get(0)[0], toTable, colPairs.get(0)[1]);
            } else {
                List<String> fkCols = colPairs.stream().map(p -> p[0]).toList();
                List<String> refCols = colPairs.stream().map(p -> p[1]).toList();
                info.addCompositeForeignKey(fromTable, fkCols, toTable, refCols);
            }
        }
    }

    private void removeExcludedViews(Map<String, SchemaInfo> perSchema) {
        for (var entry : perSchema.entrySet()) {
            SchemaInfo info = entry.getValue();
            for (String excluded : EXCLUDED_VIEWS) {
                if (info.hasTable(excluded)) {
                    info.removeTable(excluded);
                }
            }
        }
    }

    @Override
    public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name, column_name, data_type, udt_name, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """, rs -> {
            String table = rs.getString(COL_TABLE_NAME);
            String col = rs.getString(COL_COLUMN_NAME);
            String type = rs.getString(COL_DATA_TYPE);
            String udtName = rs.getString(COL_UDT_NAME);
            Integer charMaxLen = rs.getObject(COL_CHARACTER_MAXIMUM_LENGTH) != null
                    ? rs.getInt(COL_CHARACTER_MAXIMUM_LENGTH) : null;
            if ("USER-DEFINED".equals(type) && udtName != null) {
                type = udtName;
                info.addColumnEnumType(table, col, udtName);
            }
            if ("ARRAY".equals(type) && udtName != null) {
                type = udtName;
            }
            if (type.equals("bit") && charMaxLen != null) {
                type = "bit(" + charMaxLen + ")";
            } else if (type.equals("bit varying") && charMaxLen != null) {
                type = "bit varying(" + charMaxLen + ")";
            }
            info.addColumn(table, col, type);
            info.setTableSchema(table, schema);
        }, schema);
    }

    @Override
    public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        Map<String, List<String[]>> constraintColumns = new LinkedHashMap<>();
        Map<String, String[]> constraintTables = new HashMap<>();

        jdbc.query("""
            SELECT
                c.conname AS constraint_name,
                cl.relname AS from_table,
                a.attname AS from_column,
                clf.relname AS to_table,
                af.attname AS to_column,
                u.ord AS ordinal_position
            FROM pg_constraint c
            JOIN pg_class cl ON c.conrelid = cl.oid
            JOIN pg_class clf ON c.confrelid = clf.oid
            JOIN pg_namespace n ON c.connamespace = n.oid
            CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS u(from_attnum, to_attnum, ord)
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = u.from_attnum
            JOIN pg_attribute af ON af.attrelid = c.confrelid AND af.attnum = u.to_attnum
            WHERE c.contype = 'f' AND n.nspname = ?
            ORDER BY c.conname, u.ord
            """, rs -> {
            String constraintName = rs.getString("constraint_name");
            String fromTable = rs.getString("from_table");
            String fromCol = rs.getString("from_column");
            String toTable = rs.getString("to_table");
            String toCol = rs.getString("to_column");
            constraintTables.putIfAbsent(constraintName, new String[]{fromTable, toTable});
            constraintColumns.computeIfAbsent(constraintName, k -> new ArrayList<>())
                    .add(new String[]{fromCol, toCol});
        }, schema);

        for (var entry : constraintColumns.entrySet()) {
            String[] tables = constraintTables.get(entry.getKey());
            String fromTable = tables[0];
            String toTable = tables[1];
            List<String[]> colPairs = entry.getValue();

            if (colPairs.size() == 1) {
                info.addForeignKey(fromTable, colPairs.get(0)[0], toTable, colPairs.get(0)[1]);
            } else {
                List<String> fkCols = colPairs.stream().map(p -> p[0]).toList();
                List<String> refCols = colPairs.stream().map(p -> p[1]).toList();
                info.addCompositeForeignKey(fromTable, fkCols, toTable, refCols);
            }
        }
    }

    @Override
    public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name FROM information_schema.views
            WHERE table_schema = ?
            """, (RowCallbackHandler) rs -> info.addView(rs.getString(COL_TABLE_NAME)), schema);

        // Materialized views
        jdbc.query("""
            SELECT c.relname AS table_name
            FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relkind = 'm'
            """, (RowCallbackHandler) rs -> info.addView(rs.getString(COL_TABLE_NAME)), schema);

        // Materialized view columns
        jdbc.query("""
            SELECT c.relname AS table_name, a.attname AS column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type
            FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE n.nspname = ? AND c.relkind = 'm'
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """, rs -> {
            info.addColumn(rs.getString(COL_TABLE_NAME), rs.getString(COL_COLUMN_NAME), rs.getString(COL_DATA_TYPE));
            info.setTableSchema(rs.getString(COL_TABLE_NAME), schema);
        }, schema);
    }

    @Override
    public void loadStoredProcedures(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT p.proname AS proc_name,
                   pg_get_function_identity_arguments(p.oid) AS args_signature
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = ? AND p.prokind IN ('f', 'p')
            """, rs -> {
            String procName = rs.getString("proc_name");
            String argsSig = rs.getString(COL_ARGS_SIGNATURE);
            List<SchemaInfo.ProcParam> params = parseProcArgs(argsSig);
            info.addStoredProcedure(procName, new SchemaInfo.ProcedureInfo(procName, params));
        }, schema);
    }

    @Override
    public void loadEnums(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT t.typname, e.enumlabel
            FROM pg_type t
            JOIN pg_enum e ON t.oid = e.enumtypid
            JOIN pg_namespace n ON t.typnamespace = n.oid
            WHERE n.nspname = ?
            ORDER BY t.typname, e.enumsortorder
            """, (RowCallbackHandler) rs -> info.addEnumValue(rs.getString("typname"), rs.getString("enumlabel")), schema);
    }

    @Override
    public void loadCompositeTypes(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT t.typname, a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS data_type
            FROM pg_type t
            JOIN pg_namespace n ON t.typnamespace = n.oid
            JOIN pg_class c ON c.oid = t.typrelid
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            WHERE n.nspname = ? AND t.typtype = 'c' AND c.relkind = 'c'
            ORDER BY t.typname, a.attnum
            """, (RowCallbackHandler) rs -> info.addCompositeTypeField(rs.getString("typname"), rs.getString("attname"), rs.getString(COL_DATA_TYPE)), schema);
    }

    @Override
    public void loadComputedFields(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT p.proname AS function_name,
                   c.relname AS table_name,
                   pg_get_function_result(p.oid) AS return_type
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            JOIN pg_type t ON p.proargtypes[0] = t.oid
            JOIN pg_class c ON t.typrelid = c.oid
            WHERE n.nspname = ? AND array_length(p.proargtypes, 1) = 1
            """, (RowCallbackHandler) rs -> info.addComputedField(rs.getString(COL_TABLE_NAME), rs.getString("function_name"), rs.getString("return_type")), schema);
    }

    private List<SchemaInfo.ProcParam> parseProcArgs(String argsSig) {
        List<SchemaInfo.ProcParam> params = new ArrayList<>();
        if (argsSig == null || argsSig.isBlank()) return params;
        for (String part : argsSig.split(",")) {
            part = part.trim();
            String[] tokens = part.split("\\s+");
            if (tokens.length >= 3) {
                String mode = tokens[0].toUpperCase();
                String paramName = tokens[1];
                String paramType = String.join(" ", Arrays.copyOfRange(tokens, 2, tokens.length));
                params.add(new SchemaInfo.ProcParam(mode, paramName, paramType));
            } else if (tokens.length == 2) {
                params.add(new SchemaInfo.ProcParam("IN", tokens[0], tokens[1]));
            }
        }
        return params;
    }
}
