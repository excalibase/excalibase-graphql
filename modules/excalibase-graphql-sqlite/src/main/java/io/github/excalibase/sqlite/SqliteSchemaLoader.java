package io.github.excalibase.sqlite;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.spi.SchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflects a SQLite database through its PRAGMA table-valued functions: tables and views from
 * {@code sqlite_schema}, columns, primary keys and not-null flags from {@code pragma_table_info}, foreign keys
 * from {@code pragma_foreign_key_list} and unique indexes from {@code pragma_index_list}. A file has one schema,
 * named {@code main}; attached databases are not reflected.
 *
 * <p>A table with no primary key is keyed by its first unique index whose columns are all NOT NULL, so rows can
 * still be written and read back by key; one with neither is read-only to keyed writes.
 */
public class SqliteSchemaLoader implements SchemaLoader {

    public static final String MAIN = "main";

    private static final String COLUMNS = "SELECT name, type, \"notnull\", pk FROM pragma_table_info(?, ?)"
            + " ORDER BY cid";
    private static final String FOREIGN_KEYS = "SELECT id, seq, \"table\", \"from\", \"to\""
            + " FROM pragma_foreign_key_list(?, ?) ORDER BY id, seq";
    private static final String UNIQUE_INDEXES = "SELECT name FROM pragma_index_list(?, ?) WHERE \"unique\" = 1"
            + " AND partial = 0 ORDER BY seq";
    private static final String INDEX_COLUMNS = "SELECT name FROM pragma_index_info(?, ?) ORDER BY seqno";

    /** The schema name is spliced as an identifier, so it is quoted; only a loaded schema's own name reaches it. */
    private static String objects(String schema) {
        return "SELECT name, type FROM \"" + schema.replace("\"", "\"\"") + "\".sqlite_schema"
                + " WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name";
    }

    private record Table(String name, boolean view) {}

    private record Column(String name, boolean notNull, int primaryKeyPosition) {}

    @Override
    public void loadAll(JdbcTemplate jdbc, List<String> schemas, Map<String, SchemaInfo> perSchema) {
        for (String schema : schemas) {
            SchemaInfo info = perSchema.computeIfAbsent(schema, k -> new SchemaInfo());
            loadColumns(jdbc, schema, info);
            loadPrimaryKeys(jdbc, schema, info);
            loadForeignKeys(jdbc, schema, info);
            loadViews(jdbc, schema, info);
        }
    }

    private static List<Table> tables(JdbcTemplate jdbc, String schema) {
        return jdbc.query(objects(schema), (rs, row) -> new Table(rs.getString("name"), "view".equals(rs.getString("type"))));
    }

    private static List<Column> columnsOf(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.query(COLUMNS, (rs, row) -> new Column(rs.getString("name"), rs.getInt("notnull") == 1,
                rs.getInt("pk")), table, schema);
    }

    @Override
    public void loadColumns(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        for (Table table : tables(jdbc, schema)) {
            info.setTableSchema(table.name(), schema);
            jdbc.query(COLUMNS, rs -> {
                SqliteColumnTypes.register(info, table.name(), rs.getString("name"), rs.getString("type"));
                if (isRowIdAlias(jdbc, schema, table.name(), rs.getString("name"), rs.getString("type"), rs.getInt("pk"))) {
                    info.addAutoIncrementColumn(table.name(), rs.getString("name"));
                }
            }, table.name(), schema);
        }
    }

    /** {@code INTEGER PRIMARY KEY} (alone, in a table with rowids) is an alias of the rowid: SQLite fills it in. */
    private static boolean isRowIdAlias(JdbcTemplate jdbc, String schema, String table, String column, String type, int pk) {
        if (pk != 1 || type == null || !"integer".equalsIgnoreCase(type.trim())) {
            return false;
        }
        Integer keyColumns = jdbc.queryForObject("SELECT count(*) FROM pragma_table_info(?, ?) WHERE pk > 0",
                Integer.class, table, schema);
        return keyColumns != null && keyColumns == 1;
    }

    @Override
    public void loadPrimaryKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        for (Table table : tables(jdbc, schema)) {
            List<Column> columns = columnsOf(jdbc, schema, table.name());
            List<Column> key = columns.stream().filter(column -> column.primaryKeyPosition() > 0)
                    .sorted((left, right) -> Integer.compare(left.primaryKeyPosition(), right.primaryKeyPosition()))
                    .toList();
            List<String> names = key.isEmpty() && !table.view() ? uniqueKey(jdbc, schema, table.name(), columns)
                    : key.stream().map(Column::name).toList();
            names.forEach(name -> info.addPrimaryKey(table.name(), name));
        }
    }

    /** The first unique index whose columns cannot be NULL, or none. */
    private static List<String> uniqueKey(JdbcTemplate jdbc, String schema, String table, List<Column> columns) {
        Map<String, Boolean> notNull = new LinkedHashMap<>();
        columns.forEach(column -> notNull.put(column.name(), column.notNull()));
        for (String index : jdbc.queryForList(UNIQUE_INDEXES, String.class, table, schema)) {
            List<String> indexed = jdbc.queryForList(INDEX_COLUMNS, String.class, index, schema);
            if (!indexed.isEmpty() && indexed.stream().allMatch(name -> Boolean.TRUE.equals(notNull.get(name)))) {
                return indexed;
            }
        }
        return List.of();
    }

    @Override
    public void loadForeignKeys(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        for (Table table : tables(jdbc, schema)) {
            Map<Integer, List<String[]>> constraints = new LinkedHashMap<>();
            jdbc.query(FOREIGN_KEYS, rs -> {
                constraints.computeIfAbsent(rs.getInt("id"), id -> new ArrayList<>())
                        .add(new String[] {rs.getString("table"), rs.getString("from"), rs.getString("to")});
            }, table.name(), schema);
            constraints.values().forEach(key -> addForeignKey(jdbc, schema, info, table.name(), key));
        }
    }

    /** A key with no named target columns points at the parent's primary key, in order. */
    private static void addForeignKey(JdbcTemplate jdbc, String schema, SchemaInfo info, String table, List<String[]> key) {
        String parent = key.getFirst()[0];
        List<String> from = key.stream().map(part -> part[1]).toList();
        List<String> to = key.stream().map(part -> part[2]).toList();
        if (to.stream().anyMatch(column -> column == null)) {
            to = columnsOf(jdbc, schema, parent).stream().filter(column -> column.primaryKeyPosition() > 0)
                    .sorted((left, right) -> Integer.compare(left.primaryKeyPosition(), right.primaryKeyPosition()))
                    .map(Column::name).toList();
        }
        if (to.size() != from.size()) {
            return;
        }
        if (from.size() == 1) {
            info.addForeignKey(table, from.getFirst(), parent, to.getFirst());
        } else {
            info.addCompositeForeignKey(table, from, parent, to);
        }
    }

    @Override
    public void loadViews(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        tables(jdbc, schema).stream().filter(Table::view).forEach(view -> info.addView(view.name()));
    }

    /** SQLite has no routines to reflect: tracked functions are Postgres-only. */
    @Override
    public void loadFunctions(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // Nothing to reflect.
    }

    @Override
    public void loadEnums(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // SQLite has no enum type.
    }

    @Override
    public void loadCompositeTypes(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // SQLite has no composite types.
    }

    @Override
    public void loadComputedFields(JdbcTemplate jdbc, String schema, SchemaInfo info) {
        // SQLite has no computed fields in the Postgres sense.
    }
}
