package io.github.excalibase.permissions.compile;

import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.RequestGuard;
import io.github.excalibase.compiler.KeyedWrites;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.InsertPermission;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.permissions.UpdatePermission;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.sqlite.SqliteDialect;
import io.github.excalibase.sqlite.SqliteSchemaLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;

/**
 * The differential fixture on SQLite: the same tables as the Postgres fixture, in a schema named {@code public}
 * (an attached database, so the cases' {@code public.notes} resolve), holding the Postgres rows copied with an
 * explicit type mapping and reflected by the engine's own loader. One connection serves it, as the attachment
 * belongs to the connection.
 */
final class SqliteDifferentialFixture {

    private static final String ROLE = "reader";
    private static final String WRITTEN = "w";

    private static SqliteDifferentialFixture started;

    private final SingleConnectionDataSource dataSource;
    private final SchemaInfo schema;

    private SqliteDifferentialFixture(SingleConnectionDataSource dataSource, SchemaInfo schema) {
        this.dataSource = dataSource;
        this.schema = schema;
    }

    static synchronized SqliteDifferentialFixture get() {
        if (started == null) {
            started = start();
        }
        return started;
    }

    SchemaInfo schema() {
        return schema;
    }

    private static SqliteDifferentialFixture start() {
        SingleConnectionDataSource source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("ATTACH DATABASE ':memory:' AS public");
        jdbc.execute("CREATE TABLE public.orgs (id INT PRIMARY KEY, name TEXT)");
        jdbc.execute("CREATE TABLE public.members (org_id INT REFERENCES orgs(id), user_id UUID, role TEXT,"
                + " PRIMARY KEY (org_id, user_id))");
        jdbc.execute("CREATE TABLE public.notes (id INT PRIMARY KEY, owner_id UUID, org_id INT, title TEXT,"
                + " amount NUMERIC, status TEXT, created_at TIMESTAMPTZ, priority INT, pinned BOOLEAN, due DATE,"
                + " views BIGINT, mood TEXT, ip TEXT, local_at TIMESTAMP, score DOUBLE, ratio REAL,"
                + " FOREIGN KEY (org_id) REFERENCES orgs(id),"
                + " FOREIGN KEY (org_id, owner_id) REFERENCES members(org_id, user_id))");
        for (String table : List.of(ORGS, MEMBERS, NOTES)) {
            copy(table, jdbc);
        }
        return new SqliteDifferentialFixture(source, reflect(jdbc));
    }

    private static void copy(String table, JdbcTemplate sqlite) {
        new JdbcTemplate(DifferentialFixture.dataSource()).query("SELECT * FROM " + table + " ORDER BY 1",
                (ResultSetExtractor<Void>) rows -> {
                    ResultSetMetaData meta = rows.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add("\"" + meta.getColumnName(i) + "\"");
                    }
                    String insert = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
                    while (rows.next()) {
                        Object[] values = new Object[columns.size()];
                        for (int i = 1; i <= columns.size(); i++) {
                            values[i - 1] = sqliteValue(meta.getColumnTypeName(i), rows.getObject(i));
                        }
                        sqlite.update(insert, values);
                    }
                    return null;
                });
    }

    /**
     * Postgres value to SQLite value: instants and dates as the engine stores them (fixed-width ISO text in UTC), a
     * real as the double of its shortest decimal (SQLite has no single precision), enums and inet as text.
     * SQLite cannot store NaN, so a fixture holding one is refused.
     */
    private static Object sqliteValue(String postgresType, Object value) throws SQLException {
        return switch (value) {
            case null -> null;
            case Timestamp timestamp when "timestamptz".equals(postgresType) ->
                    SqliteSql.STORED_TIMESTAMPTZ.format(timestamp.toInstant());
            case Timestamp timestamp -> SqliteSql.STORED_TIMESTAMP.format(timestamp.toLocalDateTime());
            case Date date -> date.toLocalDate().toString();
            case Double number when number.isNaN() -> throw new SQLException("SQLite cannot store NaN");
            case Float number when number.isNaN() -> throw new SQLException("SQLite cannot store NaN");
            case Float number -> Double.parseDouble(number.toString());
            case Boolean flag -> flag ? 1 : 0;
            case Number number -> number;
            case UUID id -> id.toString();
            default -> value.toString();
        };
    }

    /** As the engine reflects a database: the loader per schema, merged under {@code schema.table}. */
    private static SchemaInfo reflect(JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        new SqliteSchemaLoader().loadAll(jdbc, List.of("public"), perSchema);
        SchemaInfo loaded = perSchema.get("public");
        SchemaInfo merged = new SchemaInfo();
        for (String table : loaded.getTableNames()) {
            String key = "public." + table;
            merged.setTableSchema(key, "public");
            for (String column : loaded.getColumns(table)) {
                merged.addColumn(key, column, loaded.getColumnType(table, column));
                merged.addColumnDeclaration(key, column, loaded.getColumnDeclaration(table, column));
            }
            loaded.getPrimaryKeys(table).forEach(column -> merged.addPrimaryKey(key, column));
        }
        loaded.getAllForwardFks().forEach((field, fk) -> {
            String from = "public." + field.substring(0, field.lastIndexOf('.'));
            if (fk.isComposite()) {
                merged.addCompositeForeignKey(from, fk.fkColumns(), "public." + fk.refTable(), fk.refColumns());
            } else {
                merged.addForeignKey(from, fk.fkColumn(), "public." + fk.refTable(), fk.refColumn());
            }
        });
        return merged;
    }

    /** The keys of the rows {@code filter} keeps, compiled over {@code table} aliased {@code t}. */
    TreeSet<String> rowsKept(String table, SqlFragment filter) {
        String sql = "SELECT " + keySql(table, "t") + " FROM " + table + " t WHERE " + filter.sql();
        return new TreeSet<>(new NamedParameterJdbcTemplate(dataSource)
                .queryForList(sql, new MapSqlParameterSource(filter.params()), String.class));
    }

    /** The keys of the rows a DELETE guarded by {@code filter} removes; rolled back, foreign keys unchecked. */
    TreeSet<String> rowsDeleted(String table, SqlFragment filter) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            status.setRollbackOnly();
            String keys = "SELECT " + keySql(table, "t") + " FROM " + table + " t";
            TreeSet<String> before = new TreeSet<>(jdbc.queryForList(keys, String.class));
            new NamedParameterJdbcTemplate(jdbc).update("DELETE FROM " + table + " AS t WHERE " + filter.sql(),
                    new MapSqlParameterSource(filter.params()));
            before.removeAll(jdbc.queryForList(keys, String.class));
            return before;
        });
    }

    List<Map<String, Object>> allRows(String table) {
        return new JdbcTemplate(dataSource).queryForList("SELECT * FROM " + table);
    }

    RequestGuard writeGuard(String table, BoolExp expression) {
        TablePermissions permissions = new TablePermissions(table, ROLE,
                Optional.of(new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, null, false)),
                Optional.of(new InsertPermission(expression, ColumnSet.ALL, Map.of())),
                Optional.of(new UpdatePermission(BoolExp.TRUE, expression, ColumnSet.ALL, Map.of())), Optional.empty());
        return AccessPlan.forRole(PermissionDialect.SQLITE, schema,
                        new RolePermissions(ROLE, Map.of(table, permissions), List.of(), Set.of()))
                .guard(PermissionExpressionDifferentialTest.VARIABLES);
    }

    /**
     * The rows whose own write the engine accepts under {@code guard}'s check, as {@link KeyedWrites} runs it:
     * an INSERT puts each row back after deleting it, an UPDATE sets a column to itself. Rolled back.
     */
    TreeSet<String> writesAccepted(String table, RequestGuard guard, boolean insert) {
        RlsWhereContributor.Contribution check = guard.check(table, WRITTEN, insert ? RlsOp.INSERT : RlsOp.UPDATE);
        KeyedWrites keyed = new KeyedWrites(new SqliteDialect(), new KeyedWrites.Target(table, table, WRITTEN,
                schema.getPrimaryKeys(table), false), null,
                check == null ? null : new KeyedWrites.Condition(check.sql(), check.params()));
        TreeSet<String> accepted = new TreeSet<>();
        for (Map<String, Object> row : allRows(table)) {
            String key = DifferentialFixture.key(table, row);
            if (writeAccepted(table, keyed, row, key, insert)) {
                accepted.add(key);
            }
        }
        return accepted;
    }

    private boolean writeAccepted(String table, KeyedWrites keyed, Map<String, Object> row, String key, boolean insert) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        String where = keySql(table, WRITTEN) + " = :row_key";
        Map<String, Object> params = Map.of("row_key", key);
        return Boolean.TRUE.equals(new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            status.setRollbackOnly();
            try {
                if (insert) {
                    named.update("CREATE TEMP TABLE row_copy AS SELECT * FROM " + table + " " + WRITTEN + " WHERE " + where,
                            params);
                    named.update("DELETE FROM " + table + " AS " + WRITTEN + " WHERE " + where, params);
                    keyed.insert(List.of(new KeyedWrites.Insert("INSERT INTO " + table + " SELECT * FROM row_copy",
                            Map.of(), row)), false).run(named, false);
                } else {
                    String column = schema.getPrimaryKeys(table).getFirst();
                    String set = "\"" + column + "\" = " + WRITTEN + ".\"" + column + "\"";
                    keyed.update("UPDATE " + table + " AS " + WRITTEN + " SET " + set + " WHERE " + where, where,
                            params, Map.of()).run(named, false);
                }
                return true;
            } catch (PermissionCheckFailedException _) {
                return false;
            } finally {
                jdbc.execute("DROP TABLE IF EXISTS temp.row_copy");
            }
        }));
    }

    /** {@code SELECT} list naming a row the way the Postgres fixture does ({@code 1}, {@code 1:<uuid>}). */
    static String keySql(String table, String alias) {
        return MEMBERS.equals(table)
                ? alias + ".\"org_id\" || ':' || " + alias + ".\"user_id\""
                : "CAST(" + alias + ".\"id\" AS TEXT)";
    }
}
