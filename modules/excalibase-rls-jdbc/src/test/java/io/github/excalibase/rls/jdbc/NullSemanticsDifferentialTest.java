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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native Postgres RLS is the oracle for NULL handling: for every operator the
 * realtime matcher (in memory) and the query path (emitted SQL) must keep exactly
 * the rows Postgres keeps, for a signed-in caller and for an anonymous one whose
 * id is NULL. DENY is compared with a RESTRICTIVE policy over NOT (predicate).
 */
class NullSemanticsDifferentialTest {

    private static final String UID = "NULLIF(current_setting('rls.uid', true), '')";

    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void setUp() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE null_t (id INT PRIMARY KEY, owner_txt TEXT, status TEXT)");
            s.execute("INSERT INTO null_t VALUES (1, '1', 'active'), (2, '2', 'archived'),"
                + " (3, NULL, 'active'), (4, '1', NULL), (5, NULL, NULL)");
            s.execute("CREATE ROLE app_role NOLOGIN");
            s.execute("GRANT SELECT ON null_t TO app_role");
            s.execute("ALTER TABLE null_t ENABLE ROW LEVEL SECURITY");
        }
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) postgres.stop();
    }

    static Stream<Arguments> allowCases() {
        return Stream.of(
            Arguments.of(rule("owner_txt", RuleOperator.EQ, "{{currentUserId}}"), "owner_txt = " + UID),
            Arguments.of(rule("owner_txt", RuleOperator.NEQ, "{{currentUserId}}"), "owner_txt <> " + UID),
            Arguments.of(rule("owner_txt", RuleOperator.IN, "{{currentUserId}}"), "owner_txt IN (" + UID + ")"),
            Arguments.of(rule("owner_txt", RuleOperator.NOT_IN, "{{currentUserId}}"), "owner_txt NOT IN (" + UID + ")"),
            Arguments.of(rule("owner_txt", RuleOperator.LIKE, "{{currentUserId}}"), "owner_txt LIKE " + UID),
            Arguments.of(rule("owner_txt", RuleOperator.NOT_LIKE, "{{currentUserId}}"), "owner_txt NOT LIKE " + UID),
            Arguments.of(rule("status", RuleOperator.EQ, "active"), "status = 'active'"),
            Arguments.of(rule("status", RuleOperator.NEQ, "archived"), "status <> 'archived'"),
            Arguments.of(rule("status", RuleOperator.IN, "active,archived"), "status IN ('active', 'archived')"),
            Arguments.of(rule("status", RuleOperator.NOT_IN, "archived,deleted"), "status NOT IN ('archived', 'deleted')"),
            Arguments.of(rule("status", RuleOperator.LIKE, "act%"), "status LIKE 'act%'"),
            Arguments.of(rule("status", RuleOperator.NOT_LIKE, "arch%"), "status NOT LIKE 'arch%'"),
            Arguments.of(rule("status", RuleOperator.IS_NULL, null), "status IS NULL"),
            Arguments.of(rule("status", RuleOperator.IS_NOT_NULL, null), "status IS NOT NULL"));
    }

    @ParameterizedTest(name = "ALLOW {1} as {2}")
    @MethodSource("callers")
    void allowMatchesPostgres(Rule rule, String predicate, String caller) throws Exception {
        List<Policy> policies = List.of(policy(PolicyEffect.ALLOW, rule));
        List<Integer> oracle = nativeRows(predicate, null, caller);
        assertBothPathsEqual(policies, caller, oracle);
    }

    @ParameterizedTest(name = "DENY {1} as {2}")
    @MethodSource("callers")
    void denyMatchesPostgres(Rule rule, String predicate, String caller) throws Exception {
        List<Policy> policies = List.of(
            policy(PolicyEffect.ALLOW, rule("id", RuleOperator.IS_NOT_NULL, null)),
            policy(PolicyEffect.DENY, rule));
        List<Integer> oracle = nativeRows("true", "NOT (" + predicate + ")", caller);
        assertBothPathsEqual(policies, caller, oracle);
    }

    static Stream<Arguments> callers() {
        return allowCases().flatMap(c -> Stream.of("1", "").map(uid ->
            Arguments.of(c.get()[0], c.get()[1], uid)));
    }

    private static void assertBothPathsEqual(List<Policy> policies, String caller, List<Integer> oracle) {
        UserContext ctx = ctx(caller.isEmpty() ? null : caller);
        assertThat(engineRows(policies, ctx)).as("query path vs Postgres").isEqualTo(oracle);
        assertThat(inMemoryRows(policies, ctx)).as("realtime path vs Postgres").isEqualTo(oracle);
    }

    private static List<Integer> nativeRows(String permissive, String restrictive, String uid) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            List<Integer> ids = new ArrayList<>();
            try (Statement s = c.createStatement()) {
                s.execute("CREATE POLICY p_allow ON null_t FOR SELECT TO app_role USING (" + permissive + ")");
                if (restrictive != null) {
                    s.execute("CREATE POLICY p_deny ON null_t AS RESTRICTIVE FOR SELECT TO app_role USING ("
                        + restrictive + ")");
                }
                s.execute("SET ROLE app_role");
                s.execute("SET rls.uid = '" + uid + "'");
                try (ResultSet rs = s.executeQuery("SELECT id FROM null_t ORDER BY id")) {
                    while (rs.next()) ids.add(rs.getInt(1));
                }
            } finally {
                c.rollback();
            }
            return ids;
        }
    }

    private static List<Integer> engineRows(List<Policy> policies, UserContext ctx) {
        SqlFilter filter = new JdbcEvaluator(policies).compile("null_t", ctx, Operation.SELECT);
        String where = filter.sql().isBlank() ? "TRUE" : filter.sql();
        return new NamedParameterJdbcTemplate(dataSource).queryForList(
            "SELECT id FROM null_t WHERE " + where + " ORDER BY id",
            new MapSqlParameterSource(filter.params()), Integer.class);
    }

    private static List<Integer> inMemoryRows(List<Policy> policies, UserContext ctx) {
        RowMatcher matcher = new RowMatcher(policies);
        List<Map<String, Object>> rows = new NamedParameterJdbcTemplate(dataSource).queryForList(
            "SELECT id, owner_txt, status FROM null_t ORDER BY id", new MapSqlParameterSource());
        List<Integer> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (matcher.matches("null_t", row, ctx, Operation.SELECT)) kept.add((Integer) row.get("id"));
        }
        return kept;
    }

    private static Rule rule(String field, RuleOperator op, String value) {
        return new Rule(field, FieldType.STRING, op, value);
    }

    private static Policy policy(PolicyEffect effect, Rule rule) {
        return new Policy("p-" + effect, "p", "null_t", effect, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(rule), List.of(Assignment.all()));
    }

    private static UserContext ctx(String uid) {
        return new UserContext() {
            @Override public String userId() { return uid; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
