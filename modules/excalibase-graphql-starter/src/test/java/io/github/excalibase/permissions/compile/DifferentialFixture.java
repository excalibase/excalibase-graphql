package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.schema.SchemaInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.TreeSet;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.U2;
import static io.github.excalibase.permissions.compile.FixtureSchema.U3;

/**
 * One Postgres for the differential tests, started on first use and reaped by Testcontainers:
 * orgs, members and notes with NULLs in every nullable column.
 */
final class DifferentialFixture {

    private static final String[] SCHEMA_SQL = {
            "CREATE TYPE public.mood AS ENUM ('sad', 'ok', 'happy')",
            "CREATE TABLE public.orgs (id int PRIMARY KEY, name text)",
            "CREATE TABLE public.members (org_id int REFERENCES public.orgs(id), user_id uuid, role text,"
                    + " PRIMARY KEY (org_id, user_id))",
            "CREATE TABLE public.notes (id int PRIMARY KEY, owner_id uuid, org_id int REFERENCES public.orgs(id),"
                    + " title text, amount numeric, status text, created_at timestamptz, priority int, pinned boolean,"
                    + " due date, views bigint, mood public.mood, ip inet, local_at timestamp, score double precision,"
                    + " FOREIGN KEY (org_id, owner_id) REFERENCES public.members(org_id, user_id))",
            "CREATE ROLE app_role NOLOGIN",
            "GRANT USAGE ON SCHEMA public TO app_role",
            "GRANT SELECT ON public.orgs, public.members, public.notes TO app_role"
    };

    private static final String[] DATA_SQL = {
            "INSERT INTO public.orgs VALUES (1, 'acme'), (2, 'globex'), (3, NULL)",
            "INSERT INTO public.members VALUES (1, '" + U1 + "', 'admin'), (1, '" + U2 + "', 'viewer'),"
                    + " (2, '" + U2 + "', 'admin'), (2, '" + U3 + "', NULL)",
            "INSERT INTO public.notes VALUES"
                    + " (1, '" + U1 + "', 1, 'Alpha', 10.50, 'open', '2026-01-01 10:00+00', 1, true, '2026-01-15', 5,"
                    + " 'happy', '10.0.0.1', '2026-01-01 10:00', 1.5),"
                    + " (2, '" + U2 + "', 1, 'beta', 0, 'closed', '2026-02-01 00:00+00', 2, false, '2026-07-01',"
                    + " 1000000000001, 'sad', '10.0.0.2', '2026-02-01 00:00', -2.25),"
                    + " (3, '" + U2 + "', 2, 'Gamma_x', -5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),"
                    + " (4, NULL, 2, NULL, NULL, 'open', '2026-03-01 12:30+02', 3, true, '2026-06-01', 0, 'ok', NULL,"
                    + " '2026-03-01 12:30', 0),"
                    + " (5, '" + U3 + "', NULL, 'a%b', 100, 'archived', '2025-12-31 23:59:59+00', 1, false, '2025-12-31',"
                    + " 42, 'ok', '192.168.0.1', '2025-12-31 23:59:59', 100),"
                    + " (6, NULL, NULL, 'under_score', NULL, 'open', NULL, 2, NULL, NULL, NULL, NULL, NULL, NULL, NULL),"
                    + " (7, '" + U2 + "', 2, 'ALPHA', 10.5, 'draft', '2026-01-01 10:00+00', 2, true, '2026-06-01', 7,"
                    + " 'happy', '10.0.0.1', '2026-01-01 10:00', 1.5),"
                    + " (8, '" + U1 + "', 1, 'alpha beta', NULL, 'closed', '2026-01-01 09:59:59+00', NULL, false, NULL,"
                    + " NULL, 'sad', NULL, NULL, NULL)"
    };

    private static DriverManagerDataSource dataSource;
    private static SchemaInfo schema;

    private DifferentialFixture() {
    }

    static synchronized DriverManagerDataSource dataSource() {
        if (dataSource == null) {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            DriverManagerDataSource started = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(started);
            jdbc.batchUpdate(SCHEMA_SQL);
            jdbc.batchUpdate(DATA_SQL);
            schema = reflect(jdbc);
            dataSource = started;
        }
        return dataSource;
    }

    static synchronized SchemaInfo schema() {
        dataSource();
        return schema;
    }

    /** Columns as the engine's loader records them (enum columns by their type name); FKs as it names them. */
    private static SchemaInfo reflect(JdbcTemplate jdbc) {
        SchemaInfo reflected = new SchemaInfo();
        jdbc.query("SELECT table_name, column_name, data_type, udt_name FROM information_schema.columns"
                + " WHERE table_schema = 'public' ORDER BY table_name, ordinal_position", (RowCallbackHandler) rows -> {
            String table = "public." + rows.getString("table_name");
            String column = rows.getString("column_name");
            boolean userDefined = "USER-DEFINED".equals(rows.getString("data_type"));
            reflected.addColumn(table, column, userDefined ? rows.getString("udt_name") : rows.getString("data_type"));
            reflected.setTableSchema(table, "public");
            if (userDefined) {
                reflected.addColumnEnumType(table, column, "public." + rows.getString("udt_name"));
            }
        });
        jdbc.query("SELECT t.typname, e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid"
                + " ORDER BY t.typname, e.enumsortorder", (RowCallbackHandler) rows ->
                reflected.addEnumValue("public." + rows.getString(1), rows.getString(2)));
        FixtureSchema.foreignKeys(reflected);
        return reflected;
    }

    static TreeSet<String> engineRows(String table, BoolExp expression, Map<String, String> variables) {
        SqlFragment filter = FilterCompiler.compile(expression, table, "t", schema(),
                new SessionBinding(variables), new ParamNamer("perm"));
        String sql = "SELECT " + keySql(table, "t") + " FROM " + table + " t WHERE " + filter.sql();
        return new TreeSet<>(new NamedParameterJdbcTemplate(dataSource())
                .queryForList(sql, new MapSqlParameterSource(filter.params()), String.class));
    }

    static String keySql(String table, String alias) {
        return MEMBERS.equals(table)
                ? alias + ".org_id || ':' || " + alias + ".user_id"
                : alias + ".id::text";
    }

    static String key(String table, Map<String, Object> row) {
        return MEMBERS.equals(table)
                ? row.get("org_id") + ":" + row.get("user_id")
                : String.valueOf(row.get("id"));
    }
}
