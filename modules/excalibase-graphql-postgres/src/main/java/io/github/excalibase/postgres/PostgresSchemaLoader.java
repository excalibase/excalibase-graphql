package io.github.excalibase.postgres;

import io.github.excalibase.SchemaInfo;
import io.github.excalibase.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class PostgresSchemaLoader implements SchemaLoader {

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
