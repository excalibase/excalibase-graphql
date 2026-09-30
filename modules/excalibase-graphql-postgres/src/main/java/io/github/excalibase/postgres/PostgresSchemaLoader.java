package io.github.excalibase.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import io.github.excalibase.spi.SchemaIntrospectionException;

import java.io.IOException;
import java.util.*;

public class PostgresSchemaLoader implements SchemaLoader {

    private static final String COL_TABLE_NAME = "table_name";
    private static final String COL_COLUMN_NAME = "column_name";
    private static final String COL_DATA_TYPE = "data_type";
    private static final String COL_UDT_NAME = "udt_name";
    private static final String COL_CHARACTER_MAXIMUM_LENGTH = "character_maximum_length";
    private static final String COL_TABLE_SCHEMA = "table_schema";

    private static final Set<String> EXCLUDED_VIEWS = Set.of(
            "pg_stat_statements", "pg_stat_statements_info", "pg_buffercache"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Every function and procedure of the schemas, with what deciding whether one may be tracked
     * needs: kind, volatility, SECURITY DEFINER, set-ness, the table or view whose row type it
     * returns, its arguments (mode, name, type) and how many routines share its name.
     */
    static final String FUNCTIONS_SELECT = """
                SELECT 'function' as kind, n.nspname as table_schema, p.proname as proc_name,
                       p.prokind as routine_kind, p.provolatile as volatility, p.prosecdef as security_definer,
                       p.proretset as returns_set,
                       CASE WHEN rc.oid IS NULL THEN NULL ELSE rn.nspname || '.' || rc.relname END as return_table,
                       count(*) OVER (PARTITION BY p.pronamespace, p.proname) as overloads,
                       p.pronargdefaults as default_count,
                       (SELECT coalesce(json_agg(json_build_object(
                                   'name', coalesce(p.proargnames[a.ord], ''),
                                   'type', pg_catalog.format_type(a.typ, NULL),
                                   'mode', coalesce(p.proargmodes[a.ord]::text, 'i')) ORDER BY a.ord), '[]'::json)
                          FROM unnest(coalesce(p.proallargtypes, p.proargtypes::oid[])) WITH ORDINALITY AS a(typ, ord)) as args
                FROM pg_proc p
                JOIN pg_namespace n ON p.pronamespace = n.oid
                LEFT JOIN pg_type rt ON rt.oid = p.prorettype
                LEFT JOIN pg_class rc ON rc.oid = rt.typrelid AND rc.relkind IN ('r', 'v', 'm', 'p', 'f')
                LEFT JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                WHERE n.nspname = ANY(?) AND p.prokind IN ('f', 'p')""";

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
              functions AS (
            """ + FUNCTIONS_SELECT + """

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
            UNION ALL SELECT row_to_json(x) FROM functions x
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
                case "function" -> handleFunction(node, info);
                case "computed" -> info.addComputedField(node.get(COL_TABLE_NAME).asText(),
                        node.get("function_name").asText(), node.get("return_type").asText());
                default -> { /* Ignore unknown introspection row kinds */ }
            }
        } catch (Exception e) {
            throw new SchemaIntrospectionException("Failed to parse introspection row", e);
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

    /** One reflected routine; the arguments that carry a default are the last {@code default_count} inputs. */
    static void handleFunction(JsonNode node, SchemaInfo info) {
        String name = node.get("proc_name").asText();
        int defaults = node.path("default_count").asInt(0);
        List<JsonNode> declared = new ArrayList<>();
        node.path("args").forEach(declared::add);
        long inputs = declared.stream().filter(arg -> isInputMode(arg.path("mode").asText())).count();
        List<SchemaInfo.FunctionArg> args = new ArrayList<>();
        int inputIndex = 0;
        for (JsonNode arg : declared) {
            String mode = arg.path("mode").asText();
            boolean input = isInputMode(mode);
            boolean hasDefault = input && inputIndex >= inputs - defaults;
            if (input) {
                inputIndex++;
            }
            args.add(new SchemaInfo.FunctionArg(arg.path("name").asText(), arg.path("type").asText(), mode, hasDefault));
        }
        JsonNode returnTable = node.get("return_table");
        info.addFunction(name, new SchemaInfo.FunctionInfo(node.get(COL_TABLE_SCHEMA).asText(), name,
                "p".equals(node.path("routine_kind").asText()) ? SchemaInfo.RoutineKind.PROCEDURE
                        : SchemaInfo.RoutineKind.FUNCTION,
                SchemaInfo.Volatility.fromCatalog(node.path("volatility").asText(null)),
                node.path("security_definer").asBoolean(false), node.path("returns_set").asBoolean(false),
                returnTable == null || returnTable.isNull() ? null : returnTable.asText(),
                args, node.path("overloads").asInt(1)));
    }

    private static boolean isInputMode(String mode) {
        return "i".equals(mode) || "b".equals(mode) || "v".equals(mode);
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
    public void loadFunctions(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("SELECT row_to_json(x) FROM (" + FUNCTIONS_SELECT + ") x",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", new String[] {schema})),
                (RowCallbackHandler) rs -> {
                    try {
                        handleFunction(JSON.readTree(rs.getString(1)), info);
                    } catch (IOException e) {
                        throw new SchemaIntrospectionException("Failed to parse function row", e);
                    }
                });
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
}
