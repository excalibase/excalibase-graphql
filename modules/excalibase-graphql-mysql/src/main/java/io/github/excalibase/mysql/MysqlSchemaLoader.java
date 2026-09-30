package io.github.excalibase.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.*;
import java.util.Map;

public class MysqlSchemaLoader implements SchemaLoader {

    private static final String COL_TABLE_NAME = "table_name";
    private static final String COL_COLUMN_NAME = "column_name";
    private static final String COL_TABLE_SCHEMA = "table_schema";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String buildMysqlBulkQuery(int schemaCount) {
        String inClause = "(" + String.join(",", Collections.nCopies(schemaCount, "?")) + ")";
        return """
            WITH
              cols AS (
                SELECT 'column' as kind, table_schema, table_name, column_name, data_type,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column
                FROM information_schema.columns
                WHERE table_schema IN """ + inClause + """
                ORDER BY table_schema, table_name, ordinal_position
              ),
              pkeys AS (
                SELECT 'pk' as kind, tc.table_schema, tc.table_name, kcu.column_name, NULL as data_type,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                    AND tc.table_schema = kcu.table_schema AND tc.table_name = kcu.table_name
                WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema IN """ + inClause + """
                ORDER BY tc.table_schema, tc.table_name, kcu.ordinal_position
              ),
              fkeys AS (
                SELECT 'fk' as kind, kcu.TABLE_SCHEMA as table_schema, kcu.TABLE_NAME as table_name,
                       NULL as column_name, NULL as data_type,
                       kcu.CONSTRAINT_NAME as constraint_name, kcu.COLUMN_NAME as from_column,
                       kcu.REFERENCED_TABLE_NAME as to_table, kcu.REFERENCED_COLUMN_NAME as to_column
                FROM information_schema.KEY_COLUMN_USAGE kcu
                WHERE kcu.TABLE_SCHEMA IN """ + inClause + """
                  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
              ),
              views AS (
                SELECT 'view' as kind, table_schema, table_name, NULL as column_name, NULL as data_type,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column
                FROM information_schema.views
                WHERE table_schema IN """ + inClause + """
              )
            SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) as row_json
            FROM cols
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM pkeys
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM fkeys
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM views
            """;
    }

    @Override
    public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        if (schemas.isEmpty()) return;
        String[] schemaArray = schemas.toArray(new String[0]);
        String sql = buildMysqlBulkQuery(schemas.size());
        int cteCount = 4; // cols, pkeys, fkeys, views

        jdbc.query(sql, ps -> {
            int idx = 1;
            for (int i = 0; i < cteCount; i++) {
                for (String schema : schemaArray) {
                    ps.setString(idx++, schema);
                }
            }
        }, (RowCallbackHandler) rs -> dispatchMysqlRow(rs.getString(1), perSchema));
    }

    /** Dispatch a single MySQL introspection row to its handler. */
    private void dispatchMysqlRow(String rowJson, Map<String, SchemaInfo> perSchema) {
        try {
            JsonNode node = JSON.readTree(rowJson);
            String kind = node.get("kind").asText();
            String schema = node.has(COL_TABLE_SCHEMA) && !node.get(COL_TABLE_SCHEMA).isNull()
                    ? node.get(COL_TABLE_SCHEMA).asText() : null;
            if (schema == null) return;
            SchemaInfo info = perSchema.computeIfAbsent(schema, k -> new SchemaInfo());

            switch (kind) {
                case "column" -> handleColumnRow(node, schema, info);
                case "pk" -> info.addPrimaryKey(node.get(COL_TABLE_NAME).asText(), node.get(COL_COLUMN_NAME).asText());
                case "fk" -> info.addForeignKey(
                        node.get(COL_TABLE_NAME).asText(), node.get("from_column").asText(),
                        node.get("to_table").asText(), node.get("to_column").asText());
                case "view" -> info.addView(node.get(COL_TABLE_NAME).asText());
                default -> { /* Ignore unknown introspection row kinds */ }
            }
        } catch (Exception e) {
            throw new io.github.excalibase.spi.SchemaIntrospectionException("Failed to parse MySQL introspection row", e);
        }
    }

    private void handleColumnRow(JsonNode node, String schema, SchemaInfo info) {
        String table = node.get(COL_TABLE_NAME).asText();
        String type = node.get("data_type").asText();
        if ("enum".equalsIgnoreCase(type)) type = "varchar";
        info.addColumn(table, node.get(COL_COLUMN_NAME).asText(), type);
        info.setTableSchema(table, schema);
    }

    @Override
    public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """, rs -> {
            String table = rs.getString(COL_TABLE_NAME);
            String col = rs.getString(COL_COLUMN_NAME);
            String type = rs.getString("data_type");
            // MySQL ENUM is a column constraint, not a separate type system — treat as varchar
            if ("enum".equalsIgnoreCase(type)) type = "varchar";
            info.addColumn(table, col, type);
            info.setTableSchema(table, schema);
        }, schema);
    }

    @Override
    public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT kcu.TABLE_NAME AS from_table, kcu.COLUMN_NAME AS from_column,
                   kcu.REFERENCED_TABLE_NAME AS to_table, kcu.REFERENCED_COLUMN_NAME AS to_column
            FROM information_schema.KEY_COLUMN_USAGE kcu
            WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            """, (RowCallbackHandler) rs -> info.addForeignKey(rs.getString("from_table"), rs.getString("from_column"),
                    rs.getString("to_table"), rs.getString("to_column")), schema);
    }

    @Override
    public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // MySQL views are in information_schema.views (same as Postgres)
        jdbc.query("""
            SELECT table_name FROM information_schema.views
            WHERE table_schema = ?
            """, (RowCallbackHandler) rs -> info.addView(rs.getString(COL_TABLE_NAME)), schema);
    }

    /** Routines are never exposed on MySQL: tracked functions, like permissions, are Postgres-only. */
    @Override
    public void loadFunctions(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // Nothing to reflect.
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
