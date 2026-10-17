package io.github.excalibase.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.*;
import java.util.Map;
import java.util.function.Function;

public class MysqlSchemaLoader implements SchemaLoader {

    private static final String COL_TABLE_NAME = "table_name";
    private static final String COL_COLUMN_NAME = "column_name";
    private static final String COL_TABLE_SCHEMA = "table_schema";
    private static final String COL_DATA_TYPE = "data_type";
    private static final String COL_COLUMN_TYPE = "column_type";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Columns with a FULLTEXT index of their own: MATCH (column) needs an index on exactly that column list, so a
     * column that is only part of a wider FULLTEXT index cannot be searched alone.
     */
    private static final String FULL_TEXT_COLUMNS = """
            SELECT 'fulltext' as kind, table_schema, table_name, MIN(column_name) as column_name,
                   NULL as data_type, NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column
            FROM information_schema.statistics
            WHERE index_type = 'FULLTEXT' AND table_schema IN """;
    private static final String FULL_TEXT_GROUPING = """

            GROUP BY table_schema, table_name, index_name
            HAVING COUNT(*) = 1""";

    private static String buildMysqlBulkQuery(int schemaCount) {
        String inClause = "(" + String.join(",", Collections.nCopies(schemaCount, "?")) + ")";
        return """
            WITH
              cols AS (
                SELECT 'column' as kind, table_schema, table_name, column_name, data_type, column_type,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column,
                       character_set_name, collation_name, extra
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
                       kcu.REFERENCED_TABLE_NAME as to_table, kcu.REFERENCED_COLUMN_NAME as to_column,
                       kcu.ORDINAL_POSITION as position
                FROM information_schema.KEY_COLUMN_USAGE kcu
                WHERE kcu.TABLE_SCHEMA IN """ + inClause + """
                  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
              ),
              views AS (
                SELECT 'view' as kind, table_schema, table_name, NULL as column_name, NULL as data_type,
                       NULL as constraint_name, NULL as from_column, NULL as to_table, NULL as to_column
                FROM information_schema.views
                WHERE table_schema IN """ + inClause + """
              ),
              full_text_columns AS (
                """ + FULL_TEXT_COLUMNS + inClause + FULL_TEXT_GROUPING + """
              )
            SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type, 'column_type', column_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column,
                   'character_set_name', character_set_name, 'collation_name', collation_name,
                   'extra', extra) as row_json
            FROM cols
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM pkeys
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column, 'position', position) FROM fkeys
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM views
            UNION ALL SELECT JSON_OBJECT('kind', kind, 'table_schema', table_schema, 'table_name', table_name,
                   'column_name', column_name, 'data_type', data_type,
                   'constraint_name', constraint_name, 'from_column', from_column,
                   'to_table', to_table, 'to_column', to_column) FROM full_text_columns
            """;
    }

    @Override
    public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        if (schemas.isEmpty()) return;
        String[] schemaArray = schemas.toArray(new String[0]);
        String sql = buildMysqlBulkQuery(schemas.size());
        int cteCount = 5; // cols, pkeys, fkeys, views, full_text_columns
        List<KeyColumn> keyColumns = new ArrayList<>();

        jdbc.query(sql, ps -> {
            int idx = 1;
            for (int i = 0; i < cteCount; i++) {
                for (String schema : schemaArray) {
                    ps.setString(idx++, schema);
                }
            }
        }, (RowCallbackHandler) rs -> dispatchMysqlRow(rs.getString(1), perSchema, keyColumns));
        addForeignKeys(keyColumns, perSchema::get);
    }

    /** One column of a foreign key, at {@code position} within its constraint. */
    private record KeyColumn(String schema, String table, String constraint, int position, String column,
                             String refTable, String refColumn) {}

    /** Each constraint as one key: a composite key joins on all its columns, never on one of them. */
    private static void addForeignKeys(List<KeyColumn> keyColumns, Function<String, SchemaInfo> infoOf) {
        Map<List<String>, List<KeyColumn>> constraints = new LinkedHashMap<>();
        for (KeyColumn keyColumn : keyColumns) {
            constraints.computeIfAbsent(List.of(keyColumn.schema(), keyColumn.table(), keyColumn.constraint()),
                    k -> new ArrayList<>()).add(keyColumn);
        }
        for (List<KeyColumn> columns : constraints.values()) {
            columns.sort(Comparator.comparingInt(KeyColumn::position));
            KeyColumn first = columns.getFirst();
            SchemaInfo info = infoOf.apply(first.schema());
            if (info == null) continue;
            if (columns.size() == 1) {
                info.addForeignKey(first.table(), first.column(), first.refTable(), first.refColumn());
            } else {
                info.addCompositeForeignKey(first.table(), columns.stream().map(KeyColumn::column).toList(),
                        first.refTable(), columns.stream().map(KeyColumn::refColumn).toList());
            }
        }
    }

    /** Dispatch a single MySQL introspection row to its handler. */
    private void dispatchMysqlRow(String rowJson, Map<String, SchemaInfo> perSchema, List<KeyColumn> keyColumns) {
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
                case "fk" -> keyColumns.add(new KeyColumn(schema, node.get(COL_TABLE_NAME).asText(),
                        node.get("constraint_name").asText(), node.get("position").asInt(),
                        node.get("from_column").asText(), node.get("to_table").asText(), node.get("to_column").asText()));
                case "view" -> info.addView(node.get(COL_TABLE_NAME).asText());
                case "fulltext" -> info.addFullTextColumn(node.get(COL_TABLE_NAME).asText(),
                        node.get(COL_COLUMN_NAME).asText());
                default -> { /* Ignore unknown introspection row kinds */ }
            }
        } catch (Exception e) {
            throw new io.github.excalibase.spi.SchemaIntrospectionException("Failed to parse MySQL introspection row", e);
        }
    }

    private void handleColumnRow(JsonNode node, String schema, SchemaInfo info) {
        String table = node.get(COL_TABLE_NAME).asText();
        JsonNode columnType = node.get(COL_COLUMN_TYPE);
        String column = node.get(COL_COLUMN_NAME).asText();
        MysqlColumnTypes.register(info, table, column, node.get(COL_DATA_TYPE).asText(),
                columnType == null || columnType.isNull() ? null : columnType.asText());
        info.addColumnDeclaration(table, column, new SchemaInfo.ColumnDeclaration(text(node, COL_COLUMN_TYPE),
                text(node, "character_set_name"), text(node, "collation_name")));
        addAutoIncrement(info, table, column, text(node, "extra"));
        info.setTableSchema(table, schema);
    }

    private static void addAutoIncrement(SchemaInfo info, String table, String column, String extra) {
        if (extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment")) {
            info.addAutoIncrementColumn(table, column);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    @Override
    public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        jdbc.query("""
            SELECT table_name, column_name, data_type, column_type, character_set_name, collation_name, extra
            FROM information_schema.columns
            WHERE table_schema = ?
            ORDER BY table_name, ordinal_position
            """, rs -> {
            String table = rs.getString(COL_TABLE_NAME);
            String col = rs.getString(COL_COLUMN_NAME);
            MysqlColumnTypes.register(info, table, col, rs.getString(COL_DATA_TYPE), rs.getString(COL_COLUMN_TYPE));
            info.addColumnDeclaration(table, col, new SchemaInfo.ColumnDeclaration(rs.getString(COL_COLUMN_TYPE),
                    rs.getString("character_set_name"), rs.getString("collation_name")));
            addAutoIncrement(info, table, col, rs.getString("extra"));
            info.setTableSchema(table, schema);
        }, schema);
        jdbc.query(FULL_TEXT_COLUMNS + "(?)" + FULL_TEXT_GROUPING, (RowCallbackHandler) rs ->
                info.addFullTextColumn(rs.getString(COL_TABLE_NAME), rs.getString(COL_COLUMN_NAME)), schema);
    }

    @Override
    public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        List<KeyColumn> keyColumns = new ArrayList<>();
        jdbc.query("""
            SELECT kcu.TABLE_NAME AS from_table, kcu.COLUMN_NAME AS from_column, kcu.CONSTRAINT_NAME AS constraint_name,
                   kcu.ORDINAL_POSITION AS position,
                   kcu.REFERENCED_TABLE_NAME AS to_table, kcu.REFERENCED_COLUMN_NAME AS to_column
            FROM information_schema.KEY_COLUMN_USAGE kcu
            WHERE kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            """, (RowCallbackHandler) rs -> keyColumns.add(new KeyColumn(schema, rs.getString("from_table"),
                    rs.getString("constraint_name"), rs.getInt("position"), rs.getString("from_column"),
                    rs.getString("to_table"), rs.getString("to_column"))), schema);
        addForeignKeys(keyColumns, ignored -> info);
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
        // MySQL ENUMs belong to their column: MysqlColumnTypes registers them with the column.
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
