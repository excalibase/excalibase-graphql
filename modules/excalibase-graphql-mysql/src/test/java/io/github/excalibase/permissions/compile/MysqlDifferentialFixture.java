package io.github.excalibase.permissions.compile;

import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.ChangeFilter;
import io.github.excalibase.access.ProbeRunner;
import io.github.excalibase.access.RequestGuard;
import io.github.excalibase.compiler.KeyedWrites;
import io.github.excalibase.mysql.MysqlDialect;
import io.github.excalibase.mysql.MysqlSchemaLoader;
import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.InsertPermission;
import io.github.excalibase.permissions.RolePermissions;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.permissions.UpdatePermission;
import io.github.excalibase.security.PermissionCheckFailedException;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.schema.SchemaInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
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
 * The differential fixture on MySQL 8.4: the same tables, holding the Postgres fixture's rows copied
 * with an explicit type mapping, reflected by the engine's own loader. Connections run in a session
 * time zone other than UTC, so a timestamp compared by its wall clock instead of its instant shows.
 */
final class MysqlDifferentialFixture {

    /** Case- and accent-sensitive like Postgres; the default MySQL collation is the other run. */
    enum Collation {
        BIN("utf8mb4_bin"), AI_CI("utf8mb4_0900_ai_ci");

        final String name;

        Collation(String name) {
            this.name = name;
        }
    }

    static final String SESSION_TIME_ZONE = "+05:00";

    private static final String ROLE = "reader";
    private static final String WRITTEN = "w";

    private static final DateTimeFormatter WITH_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxxx");

    private static final Map<Collation, MysqlDifferentialFixture> STARTED = new EnumMap<>(Collation.class);

    private final DriverManagerDataSource dataSource;
    private final SchemaInfo schema;

    private MysqlDifferentialFixture(DriverManagerDataSource dataSource, SchemaInfo schema) {
        this.dataSource = dataSource;
        this.schema = schema;
    }

    static synchronized MysqlDifferentialFixture of(Collation collation) {
        return STARTED.computeIfAbsent(collation, MysqlDifferentialFixture::start);
    }

    DriverManagerDataSource dataSource() {
        return dataSource;
    }

    SchemaInfo schema() {
        return schema;
    }

    private static MysqlDifferentialFixture start(Collation collation) {
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("public");
        mysql.start();
        DriverManagerDataSource started = new DriverManagerDataSource(mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "connectionTimeZone=" + SESSION_TIME_ZONE.replace("+", "%2B")
                + "&forceConnectionTimeZoneToSession=true", mysql.getUsername(), mysql.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(started);
        String table = " DEFAULT CHARSET=utf8mb4 COLLATE=" + collation.name;
        jdbc.execute("CREATE TABLE public.orgs (id int PRIMARY KEY, name varchar(100))" + table);
        jdbc.execute("CREATE TABLE public.members (org_id int, user_id char(36), role varchar(20),"
                + " PRIMARY KEY (org_id, user_id), FOREIGN KEY (org_id) REFERENCES public.orgs(id))" + table);
        jdbc.execute("CREATE TABLE public.notes (id int PRIMARY KEY, owner_id char(36), org_id int, title varchar(100),"
                + " amount decimal(12,2), status varchar(20), created_at timestamp(6) NULL, priority int,"
                + " pinned tinyint(1), due date, views bigint, mood enum('sad','ok','happy'), ip varchar(45),"
                + " local_at datetime(6), score double, ratio float,"
                + " FOREIGN KEY (org_id) REFERENCES public.orgs(id),"
                + " FOREIGN KEY (org_id, owner_id) REFERENCES public.members(org_id, user_id))" + table);
        for (String copied : List.of(ORGS, MEMBERS, NOTES)) {
            copy(copied, jdbc);
        }
        return new MysqlDifferentialFixture(started, reflect(jdbc));
    }

    /** Every row of the Postgres fixture's {@code table}, inserted into the same MySQL table. */
    private static void copy(String table, JdbcTemplate mysql) {
        new JdbcTemplate(DifferentialFixture.dataSource()).query("SELECT * FROM " + table + " ORDER BY 1",
                (ResultSetExtractor<Void>) rows -> {
                    ResultSetMetaData meta = rows.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add("`" + meta.getColumnName(i) + "`");
                    }
                    String insert = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                            + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
                    while (rows.next()) {
                        Object[] values = new Object[columns.size()];
                        for (int i = 1; i <= columns.size(); i++) {
                            values[i - 1] = mysqlValue(meta.getColumnTypeName(i), rows.getObject(i));
                        }
                        mysql.update(insert, values);
                    }
                    return null;
                });
    }

    /**
     * Postgres value to MySQL value. A timestamptz is written with its offset so MySQL stores the instant
     * whatever the session zone; a boolean becomes tinyint(1); enum, inet and uuid become text. MySQL
     * floats cannot hold NaN or the infinities, so a fixture holding one is refused.
     */
    private static Object mysqlValue(String postgresType, Object value) throws SQLException {
        return switch (value) {
            case null -> null;
            case Timestamp timestamp when "timestamptz".equals(postgresType) ->
                    WITH_OFFSET.format(OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC));
            case Timestamp timestamp -> timestamp.toLocalDateTime();
            case Date date -> date.toLocalDate();
            case Double number when !Double.isFinite(number) -> throw new SQLException("MySQL cannot store " + number);
            case Float number when !Float.isFinite(number) -> throw new SQLException("MySQL cannot store " + number);
            case Boolean flag -> flag;
            case Number number -> number;
            case UUID id -> id.toString();
            default -> value.toString();
        };
    }

    /** As the engine reflects a MySQL database: the loader per schema, merged under {@code schema.table}. */
    private static SchemaInfo reflect(JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        new MysqlSchemaLoader().loadAll(jdbc, List.of("public"), perSchema);
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
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                String keys = "SELECT " + keySql(table, "t") + " FROM " + table + " t";
                TreeSet<String> before = new TreeSet<>(jdbc.queryForList(keys, String.class));
                new NamedParameterJdbcTemplate(jdbc).update("DELETE FROM " + table + " t WHERE " + filter.sql(),
                        new MapSqlParameterSource(filter.params()));
                before.removeAll(jdbc.queryForList(keys, String.class));
                return before;
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    /** Every row of {@code table} as JDBC reads it, standing in for change images. */
    List<Map<String, Object>> allRows(String table) {
        return new JdbcTemplate(dataSource).queryForList("SELECT * FROM " + table);
    }

    ProbeRunner probes() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        return (sql, params) -> Boolean.TRUE.equals(named.queryForObject(sql, params, Boolean.class));
    }

    /** A realtime subscription on {@code table} by a role whose select filter is {@code expression}. */
    ChangeFilter subscription(String table, BoolExp expression) {
        TablePermissions permissions = new TablePermissions(table, ROLE,
                Optional.of(new SelectPermission(expression, ColumnSet.ALL, null, false)),
                Optional.empty(), Optional.empty(), Optional.empty());
        AccessPlan plan = AccessPlan.forRole(PermissionDialect.MYSQL, schema,
                new RolePermissions(ROLE, Map.of(table, permissions), List.of(), Set.of()));
        return plan.changes(table, PermissionExpressionDifferentialTest.VARIABLES).orElseThrow();
    }

    /** {@code SELECT} list naming a row the way the Postgres fixture does ({@code 1}, {@code 1:<uuid>}). */
    /** The guard of a role whose insert and update checks on {@code table} are {@code expression}, and nothing else. */
    RequestGuard writeGuard(String table, BoolExp expression) {
        TablePermissions permissions = new TablePermissions(table, ROLE,
                Optional.of(new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, null, false)),
                Optional.of(new InsertPermission(expression, ColumnSet.ALL, Map.of())),
                Optional.of(new UpdatePermission(BoolExp.TRUE, expression, ColumnSet.ALL, Map.of())), Optional.empty());
        return AccessPlan.forRole(PermissionDialect.MYSQL, schema,
                        new RolePermissions(ROLE, Map.of(table, permissions), List.of(), Set.of()))
                .guard(PermissionExpressionDifferentialTest.VARIABLES);
    }

    /**
     * The rows whose own write the engine accepts under {@code guard}'s check, as {@link KeyedWrites} runs it:
     * an INSERT puts each row back after deleting it, an UPDATE sets a column to itself. Rolled back.
     */
    TreeSet<String> writesAccepted(String table, RequestGuard guard, boolean insert) {
        RlsWhereContributor.Contribution check = guard.check(table, WRITTEN, insert ? RlsOp.INSERT : RlsOp.UPDATE);
        KeyedWrites keyed = new KeyedWrites(new MysqlDialect(), new KeyedWrites.Target(table, quoted(table), WRITTEN,
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
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                if (insert) {
                    named.update("CREATE TEMPORARY TABLE row_copy AS SELECT * FROM " + quoted(table) + " " + WRITTEN
                            + " WHERE " + where, params);
                    named.update("DELETE " + WRITTEN + " FROM " + quoted(table) + " " + WRITTEN + " WHERE " + where, params);
                    keyed.insert(List.of(new KeyedWrites.Insert("INSERT INTO " + quoted(table) + " SELECT * FROM row_copy",
                            Map.of(), row)), false).run(named, false);
                } else {
                    String column = schema.getPrimaryKeys(table).getFirst();
                    String set = WRITTEN + ".`" + column + "` = " + WRITTEN + ".`" + column + "`";
                    keyed.update("UPDATE " + quoted(table) + " " + WRITTEN + " SET " + set + " WHERE " + where, where,
                            params, Map.of()).run(named, false);
                }
                return true;
            } catch (PermissionCheckFailedException refused) {
                return false;
            } finally {
                jdbc.execute("DROP TEMPORARY TABLE IF EXISTS row_copy");
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }));
    }

    private static String quoted(String table) {
        return "`" + table.replace(".", "`.`") + "`";
    }

    static String keySql(String table, String alias) {
        return MEMBERS.equals(table)
                ? "CONCAT(" + alias + ".`org_id`, ':', " + alias + ".`user_id`)"
                : "CAST(" + alias + ".`id` AS CHAR)";
    }
}
