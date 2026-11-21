package io.github.excalibase.mongodb;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native Postgres row-level security, the oracle for which rows a rule keeps. Started once per JVM. */
final class PostgresOracle {

    static final PostgresOracle INSTANCE = new PostgresOracle();

    /** The data both databases hold: a null is stored as an explicit null for odd ids and left missing for even ones. */
    record Row(int id, String owner, String status, Integer n) {}

    static final List<Row> ROWS = List.of(
            new Row(1, "1", "active", 1), new Row(2, "2", "archived", 5), new Row(3, null, "active", 10),
            new Row(4, "1", null, null), new Row(5, null, null, 7), new Row(6, "x.y", "a.tive", 3),
            new Row(7, "3", "act\nxx", null), new Row(8, "4", "active\n", 8));

    private PostgreSQLContainer<?> postgres;
    private DriverManagerDataSource dataSource;

    private PostgresOracle() {}

    private synchronized DriverManagerDataSource dataSource() throws Exception {
        if (dataSource == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            DriverManagerDataSource source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword());
            try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE null_t (id INT PRIMARY KEY, owner_txt TEXT, status TEXT, n INT)");
                s.execute("CREATE ROLE app_role NOLOGIN");
                s.execute("GRANT SELECT ON null_t TO app_role");
                s.execute("ALTER TABLE null_t ENABLE ROW LEVEL SECURITY");
            }
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(source);
            for (Row row : ROWS) {
                jdbc.update("INSERT INTO null_t VALUES (:id, :owner, :status, :n)", new MapSqlParameterSource()
                        .addValue("id", row.id()).addValue("owner", row.owner()).addValue("status", row.status())
                        .addValue("n", row.n()));
            }
            dataSource = source;
        }
        return dataSource;
    }

    /** Ids Postgres shows {@code uid} under a permissive policy and, when given, a restrictive one. */
    List<Integer> visibleIds(String permissive, String restrictive, String uid) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            List<Integer> ids = new ArrayList<>();
            try (Statement s = c.createStatement()) {
                s.execute("CREATE POLICY p_allow ON null_t FOR SELECT TO app_role USING (" + permissive + ")");
                if (restrictive != null) {
                    s.execute("CREATE POLICY p_deny ON null_t AS RESTRICTIVE FOR SELECT TO app_role USING (" + restrictive + ")");
                }
                s.execute("SET ROLE app_role");
                s.execute("SET rls.uid = '" + uid + "'");
                try (ResultSet rs = s.executeQuery("SELECT id FROM null_t ORDER BY id")) {
                    while (rs.next()) {
                        ids.add(rs.getInt(1));
                    }
                }
            } finally {
                c.rollback();
            }
            return ids;
        }
    }

    /** The stored rows as maps, for the in-memory matcher. */
    List<Map<String, Object>> rows() throws Exception {
        return new NamedParameterJdbcTemplate(dataSource()).queryForList(
                "SELECT id, owner_txt, status, n FROM null_t ORDER BY id", new MapSqlParameterSource());
    }
}
