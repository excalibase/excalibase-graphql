package io.github.excalibase.rls.jdbc;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RowMatcher;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Native Postgres RLS is the oracle for LIKE: the realtime matcher and the
 * emitted SQL must keep exactly the rows Postgres keeps, for patterns and
 * values holding newlines, every regex metacharacter and escaped wildcards.
 */
class LikeDifferentialTest {

    private static final List<String> VALUES = List.of(
        "abc", "abc\n", "a\nb", "\nabc", "a+b", "a*b", "a?b", "a(b)", "a[b]", "a{2}", "a^b", "a$b", "a|b",
        "a.b", "axb", "aab", "aaa", "50%", "500", "a_c", "abc_", "a\\c", "a\\b", "a\\Eb", "x(y", "(a)", "^abc$");

    private static final List<String> PATTERNS = List.of(
        "abc", "abc%", "%abc", "a%", "%b", "a_b", "a_c", "%\n%", "a\nb", "a+b", "a+", "a*b", "a?b", "a(b)", "(a)",
        "[ab]", "a[b]", "a{2}", "a^b", "a$b", "a|b", "^abc$", "a.b", "%(%", "%$", "50\\%", "a\\_c", "a\\\\c",
        "a\\bc", "a\\Eb", "\\a%", "%\\%", "\\_%");

    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE like_t (id INT PRIMARY KEY, val TEXT)");
            s.execute("CREATE ROLE app_role NOLOGIN");
            s.execute("GRANT SELECT ON like_t TO app_role");
            s.execute("ALTER TABLE like_t ENABLE ROW LEVEL SECURITY");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO like_t VALUES (?, ?)")) {
                for (int i = 0; i < VALUES.size(); i++) {
                    ins.setInt(1, i + 1);
                    ins.setString(2, VALUES.get(i));
                    ins.executeUpdate();
                }
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) postgres.stop();
    }

    static Stream<String> patterns() {
        return PATTERNS.stream();
    }

    @ParameterizedTest(name = "ALLOW LIKE {0}")
    @MethodSource("patterns")
    void allowLike(String pattern) throws Exception {
        assertAllPathsEqual(List.of(policy(PolicyEffect.ALLOW, RuleOperator.LIKE, pattern)),
            "val LIKE ?", null, pattern);
    }

    @ParameterizedTest(name = "ALLOW NOT LIKE {0}")
    @MethodSource("patterns")
    void allowNotLike(String pattern) throws Exception {
        assertAllPathsEqual(List.of(policy(PolicyEffect.ALLOW, RuleOperator.NOT_LIKE, pattern)),
            "val NOT LIKE ?", null, pattern);
    }

    @ParameterizedTest(name = "DENY LIKE {0}")
    @MethodSource("patterns")
    void denyLike(String pattern) throws Exception {
        List<Policy> policies = List.of(
            policy(PolicyEffect.ALLOW, RuleOperator.IS_NOT_NULL, null),
            policy(PolicyEffect.DENY, RuleOperator.LIKE, pattern));
        assertAllPathsEqual(policies, "true", "NOT (val LIKE ?)", pattern);
    }

    @Test
    void trailingEscapeIsAnErrorNativelyAndKeepsNoRowInEngine() {
        String pattern = "abc\\";
        assertThatThrownBy(() -> nativeRows("val LIKE ?", null, pattern))
            .hasMessageContaining("must not end with escape character");
        List<Policy> policies = List.of(policy(PolicyEffect.ALLOW, RuleOperator.LIKE, pattern));
        assertThatThrownBy(() -> engineRows(policies)).isInstanceOf(Exception.class);
        assertThat(inMemoryRows(policies)).isEmpty();
        List<Policy> notLike = List.of(policy(PolicyEffect.ALLOW, RuleOperator.NOT_LIKE, pattern));
        assertThat(inMemoryRows(notLike)).isEmpty();
    }

    private static void assertAllPathsEqual(List<Policy> policies, String permissive, String restrictive,
                                            String pattern) throws Exception {
        List<Integer> oracle = nativeRows(permissive, restrictive, pattern);
        assertThat(engineRows(policies)).as("query path vs Postgres").isEqualTo(oracle);
        assertThat(inMemoryRows(policies)).as("realtime path vs Postgres").isEqualTo(oracle);
    }

    private static List<Integer> nativeRows(String permissive, String restrictive, String pattern) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            List<Integer> ids = new ArrayList<>();
            try (Statement s = c.createStatement()) {
                // CREATE POLICY cannot bind parameters, so the pattern is inlined as a quoted literal.
                String literal = "'" + pattern.replace("'", "''") + "'";
                s.execute("CREATE POLICY p_allow ON like_t FOR SELECT TO app_role USING ("
                    + permissive.replace("?", literal) + ")");
                if (restrictive != null) {
                    s.execute("CREATE POLICY p_deny ON like_t AS RESTRICTIVE FOR SELECT TO app_role USING ("
                        + restrictive.replace("?", literal) + ")");
                }
                s.execute("SET ROLE app_role");
                try (ResultSet rs = s.executeQuery("SELECT id FROM like_t ORDER BY id")) {
                    while (rs.next()) ids.add(rs.getInt(1));
                }
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                if (!c.isClosed()) c.rollback();
            }
            return ids;
        }
    }

    private static List<Integer> engineRows(List<Policy> policies) {
        SqlFilter filter = new JdbcEvaluator(policies).compile("like_t", ctx(), Operation.SELECT);
        String where = filter.sql().isBlank() ? "TRUE" : filter.sql();
        return new NamedParameterJdbcTemplate(dataSource).queryForList(
            "SELECT id FROM like_t WHERE " + where + " ORDER BY id",
            new MapSqlParameterSource(filter.params()), Integer.class);
    }

    private static List<Integer> inMemoryRows(List<Policy> policies) {
        RowMatcher matcher = new RowMatcher(policies);
        List<Map<String, Object>> rows = new NamedParameterJdbcTemplate(dataSource).queryForList(
            "SELECT id, val FROM like_t ORDER BY id", new MapSqlParameterSource());
        List<Integer> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (matcher.matches("like_t", row, ctx(), Operation.SELECT)) kept.add((Integer) row.get("id"));
        }
        return kept;
    }

    private static Policy policy(PolicyEffect effect, RuleOperator op, String value) {
        String field = op == RuleOperator.IS_NOT_NULL ? "id" : "val";
        FieldType type = op == RuleOperator.IS_NOT_NULL ? FieldType.INTEGER : FieldType.STRING;
        return new Policy("p-" + effect + op, "p", "like_t", effect, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule(field, type, op, value)), List.of(Assignment.all()));
    }

    private static UserContext ctx() {
        return new UserContext() {
            @Override public String userId() { return "1"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
