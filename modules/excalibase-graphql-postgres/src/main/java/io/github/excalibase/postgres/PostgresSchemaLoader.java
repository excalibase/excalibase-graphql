package io.github.excalibase.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class PostgresSchemaLoader implements SchemaLoader {

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
                WHERE n.nspname = ANY(?) AND p.prokind = 'p'
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
            """;

    @Override
    public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        if (schemas.isEmpty()) return;
        String[] schemaArray = schemas.toArray(new String[0]);

        // FK grouping: (schema.constraintName) → list of [fromCol, toCol] pairs + table refs
        Map<String, List<String[]>> fkColumns = new LinkedHashMap<>();
        Map<String, String[]> fkTables = new HashMap<>();

        jdbc.query(BULK_INTROSPECTION_QUERY, ps -> {
            // All 10 CTEs use the same parameter — bind it 10 times
            for (int i = 1; i <= 10; i++) {
                ps.setArray(i, ps.getConnection().createArrayOf("text", schemaArray));
            }
        }, rs -> {
            try {
                JsonNode node = JSON.readTree(rs.getString(1));
                String kind = node.get("kind").asText();
                String schema = node.has("table_schema") && !node.get("table_schema").isNull()
                        ? node.get("table_schema").asText() : null;
                if (schema == null) return;
                SchemaInfo info = perSchema.computeIfAbsent(schema, k -> new SchemaInfo());

                switch (kind) {
                    case "column" -> {
                        String table = node.get("table_name").asText();
                        String col = node.get("column_name").asText();
                        String type = node.get("data_type").asText();
                        String udtName = node.has("udt_name") && !node.get("udt_name").isNull()
                                ? node.get("udt_name").asText() : null;
                        Integer charMaxLen = node.has("character_maximum_length") && !node.get("character_maximum_length").isNull()
                                ? node.get("character_maximum_length").asInt() : null;
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
                    }
                    case "pk" -> info.addPrimaryKey(node.get("table_name").asText(), node.get("column_name").asText());
                    case "fk" -> {
                        String constraintKey = schema + "." + node.get("constraint_name").asText();
                        String fromTable = node.get("table_name").asText();
                        String fromCol = node.get("from_column").asText();
                        String toTable = node.get("to_table").asText();
                        String toCol = node.get("to_column").asText();
                        fkTables.putIfAbsent(constraintKey, new String[]{fromTable, toTable, schema});
                        fkColumns.computeIfAbsent(constraintKey, k -> new ArrayList<>())
                                .add(new String[]{fromCol, toCol});
                    }
                    case "view" -> {
                        String viewName = node.get("table_name").asText();
                        if (!EXCLUDED_VIEWS.contains(viewName)) {
                            info.addView(viewName);
                        }
                    }
                    case "matview" -> info.addView(node.get("table_name").asText());
                    case "matview_col" -> {
                        info.addColumn(node.get("table_name").asText(),
                                node.get("column_name").asText(), node.get("data_type").asText());
                        info.setTableSchema(node.get("table_name").asText(), schema);
                    }
                    case "enum" -> info.addEnumValue(node.get("udt_name").asText(), node.get("enum_label").asText());
                    case "composite" -> info.addCompositeTypeField(
                            node.get("udt_name").asText(), node.get("column_name").asText(),
                            node.get("data_type").asText());
                    case "proc" -> {
                        String procName = node.get("proc_name").asText();
                        String argsSig = node.has("args_signature") && !node.get("args_signature").isNull()
                                ? node.get("args_signature").asText() : "";
                        info.addStoredProcedure(procName, new SchemaInfo.ProcedureInfo(procName, parseProcArgs(argsSig)));
                    }
                    case "computed" -> info.addComputedField(
                            node.get("table_name").asText(), node.get("function_name").asText(),
                            node.get("return_type").asText());
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse introspection row", e);
            }
        });

        // Post-process FKs (grouped by constraint)
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
                List<String> fkCols = colPairs.stream().map(p -> p[0]).collect(Collectors.toList());
                List<String> refCols = colPairs.stream().map(p -> p[1]).collect(Collectors.toList());
                info.addCompositeForeignKey(fromTable, fkCols, toTable, refCols);
            }
        }

        // Also exclude extension views from columns (they were loaded by the column CTE)
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
            String table = rs.getString("table_name");
            String col = rs.getString("column_name");
            String type = rs.getString("data_type");
            String udtName = rs.getString("udt_name");
            Integer charMaxLen = rs.getObject("character_maximum_length") != null
                    ? rs.getInt("character_maximum_length") : null;
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
                List<String> fkCols = colPairs.stream().map(p -> p[0]).collect(Collectors.toList());
                List<String> refCols = colPairs.stream().map(p -> p[1]).collect(Collectors.toList());
                info.addCompositeForeignKey(fromTable, fkCols, toTable, refCols);
            }
        }
    }

    @Override
    public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name FROM information_schema.views
            WHERE table_schema = ?
            """, rs -> {
            info.addView(rs.getString("table_name"));
        }, schema);

        // Materialized views
        jdbc.query("""
            SELECT c.relname AS table_name
            FROM pg_class c
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname = ? AND c.relkind = 'm'
            """, rs -> {
            info.addView(rs.getString("table_name"));
        }, schema);

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
            info.addColumn(rs.getString("table_name"), rs.getString("column_name"), rs.getString("data_type"));
            info.setTableSchema(rs.getString("table_name"), schema);
        }, schema);
    }

    @Override
    public void loadStoredProcedures(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT p.proname AS proc_name,
                   pg_get_function_identity_arguments(p.oid) AS args_signature
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = ? AND p.prokind = 'p'
            """, rs -> {
            String procName = rs.getString("proc_name");
            String argsSig = rs.getString("args_signature");
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
            """, rs -> {
            info.addEnumValue(rs.getString("typname"), rs.getString("enumlabel"));
        }, schema);
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
            """, rs -> {
            info.addCompositeTypeField(rs.getString("typname"), rs.getString("attname"), rs.getString("data_type"));
        }, schema);
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
            """, rs -> {
            info.addComputedField(rs.getString("table_name"), rs.getString("function_name"), rs.getString("return_type"));
        }, schema);
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
