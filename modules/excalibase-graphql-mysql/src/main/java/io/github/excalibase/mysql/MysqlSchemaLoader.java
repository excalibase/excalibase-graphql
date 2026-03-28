package io.github.excalibase.mysql;

import io.github.excalibase.SchemaInfo;
import io.github.excalibase.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

public class MysqlSchemaLoader implements SchemaLoader {

    @Override
    public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """, rs -> {
            String table = rs.getString("table_name");
            String col = rs.getString("column_name");
            String type = rs.getString("data_type");
            // MySQL ENUM is a column constraint, not a separate type system — treat as varchar
            if ("enum".equalsIgnoreCase(type)) type = "varchar";
            info.addColumn(table, col, type);
        }, schema);
    }

    @Override
    public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT kcu.TABLE_NAME AS from_table, kcu.COLUMN_NAME AS from_column,
                   kcu.REFERENCED_TABLE_NAME AS to_table, kcu.REFERENCED_COLUMN_NAME AS to_column
            FROM information_schema.KEY_COLUMN_USAGE kcu
            WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            """, rs -> {
            info.addForeignKey(rs.getString("from_table"), rs.getString("from_column"),
                              rs.getString("to_table"), rs.getString("to_column"));
        }, schema);
    }

    @Override
    public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // MySQL views are in information_schema.views (same as Postgres)
        jdbc.query("""
            SELECT table_name FROM information_schema.views
            WHERE table_schema = ?
            """, rs -> {
            info.addView(rs.getString("table_name"));
        }, schema);
    }

    @Override
    public void loadStoredProcedures(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // Load MySQL stored procedures from information_schema
        Map<String, List<SchemaInfo.ProcParam>> procParams = new LinkedHashMap<>();

        jdbc.query("""
            SELECT r.SPECIFIC_NAME AS proc_name,
                   p.PARAMETER_NAME AS param_name,
                   p.PARAMETER_MODE AS param_mode,
                   p.DATA_TYPE AS param_type
            FROM information_schema.ROUTINES r
            LEFT JOIN information_schema.PARAMETERS p
                ON r.SPECIFIC_NAME = p.SPECIFIC_NAME AND r.ROUTINE_SCHEMA = p.SPECIFIC_SCHEMA
            WHERE r.ROUTINE_SCHEMA = ? AND r.ROUTINE_TYPE = 'PROCEDURE'
                AND (p.PARAMETER_NAME IS NOT NULL OR p.ORDINAL_POSITION IS NULL)
            ORDER BY r.SPECIFIC_NAME, p.ORDINAL_POSITION
            """, rs -> {
            String procName = rs.getString("proc_name");
            String paramName = rs.getString("param_name");
            String paramMode = rs.getString("param_mode");
            String paramType = rs.getString("param_type");
            if (paramName != null) {
                procParams.computeIfAbsent(procName, k -> new ArrayList<>())
                        .add(new SchemaInfo.ProcParam(paramMode, paramName, paramType));
            } else {
                procParams.putIfAbsent(procName, new ArrayList<>());
            }
        }, schema);

        for (var entry : procParams.entrySet()) {
            info.addStoredProcedure(entry.getKey(),
                    new SchemaInfo.ProcedureInfo(entry.getKey(), entry.getValue()));
        }
    }

    @Override
    public void loadEnums(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // MySQL enums are loaded inline during loadColumns (from column_type)
    }

    @Override
    public void loadCompositeTypes(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // MySQL does not support composite types
    }

    @Override
    public void loadComputedFields(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // MySQL does not support computed fields in the same way as PostgreSQL
    }
}
