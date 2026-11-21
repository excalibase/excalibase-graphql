package io.github.excalibase.rls.jdbc;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RelationPredicate;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Portability leg of the RLS differential suite: the same policy engine, asked
 * to emit MySQL SQL ({@link QuoteStyle#BACKTICK}), must return the SAME rows on
 * MySQL that native Postgres RLS returns (proven in the source repo's
 * DifferentialPostgresRlsTest). This is what "Postgres RLS for any DB" means —
 * enforcement compiled into portable SQL rather than native per-DB RLS.
 *
 * <p>Expected row sets below are the Postgres-oracle results; this test proves
 * the emitted SQL reproduces them on a real MySQL engine.
 */
class MySqlPortabilityTest {

    private static MySQLContainer<?> mysql;
    private static NamedParameterJdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate noBackslashEscapes;

    @BeforeAll
    static void setUp() {
        mysql = new MySQLContainer<>("mysql:8.0");
        mysql.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(
            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        ds.setDriverClassName(mysql.getDriverClassName());
        jdbc = new NamedParameterJdbcTemplate(ds);

        exec("""
            CREATE TABLE diff_t (
                id         INT PRIMARY KEY,
                owner_txt  VARCHAR(64),
                tenant_txt VARCHAR(64),
                status     VARCHAR(64),
                amount     INT,
                meta       JSON
            )""");
        exec("""
            INSERT INTO diff_t (id, owner_txt, tenant_txt, status, amount, meta) VALUES
              (1, '1', 't1', 'active', 10,  '{"region":"west","level":3}'),
              (2, '1', 't1', 'draft',  50,  '{"region":"east","level":5}'),
              (3, '2', 't2', 'active', 100, '{"region":"west","level":9}'),
              (4, '2', 't2', 'draft',  200, '{"region":"east"}'),
              (5, NULL, NULL, NULL, NULL, NULL)""");
        exec("CREATE TABLE like_t (id INT PRIMARY KEY, val VARCHAR(64))");
        String insert = "INSERT INTO like_t VALUES (:id, :val)";
        jdbc.update(insert, new MapSqlParameterSource("id", 1).addValue("val", "a%b"));
        jdbc.update(insert, new MapSqlParameterSource("id", 2).addValue("val", "axb"));
        jdbc.update(insert, new MapSqlParameterSource("id", 3).addValue("val", "a_b"));
        jdbc.update(insert, new MapSqlParameterSource("id", 4).addValue("val", "a\\b"));
        jdbc.update(insert, new MapSqlParameterSource("id", 5).addValue("val", "A%B"));
        String url = mysql.getJdbcUrl();
        DriverManagerDataSource strict = new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "sessionVariables=sql_mode=NO_BACKSLASH_ESCAPES",
            mysql.getUsername(), mysql.getPassword());
        strict.setDriverClassName(mysql.getDriverClassName());
        noBackslashEscapes = new NamedParameterJdbcTemplate(strict);
        exec("CREATE TABLE diff_members (member_user VARCHAR(64), member_tenant VARCHAR(64))");
        exec("INSERT INTO diff_members (member_user, member_tenant) VALUES ('1','t1'),('1','t2'),('2','t2')");
    }

    @AfterAll
    static void tearDown() {
        if (mysql != null) mysql.stop();
    }

    private static void exec(String sql) {
        jdbc.getJdbcTemplate().execute(sql);
    }

    // run the engine's emitted MySQL SQL and return the matching row ids
    private List<Integer> engineRowsMysql(List<Policy> policies, UserContext ctx) {
        SqlFilter f = new JdbcEvaluator(policies, List.of(), QuoteStyle.BACKTICK)
            .compile("diff_t", ctx, Operation.SELECT);
        String where = f.sql().isBlank() ? "TRUE" : f.sql();
        return jdbc.queryForList("SELECT id FROM diff_t WHERE " + where + " ORDER BY id",
            new MapSqlParameterSource(f.params()), Integer.class);
    }

    @Test @DisplayName("MySQL: owner filter (string EQ + currentUserId)")
    void ownerFilter() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("owner_txt", FieldType.STRING, RuleOperator.EQ, "{{currentUserId}}"))),
            ctx("1", null, null))).containsExactly(1, 2);
    }

    @Test @DisplayName("MySQL: tenant filter (currentTenantId)")
    void tenantFilter() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("tenant_txt", FieldType.STRING, RuleOperator.EQ, "{{currentTenantId}}"))),
            ctx(null, "t2", null))).containsExactly(3, 4);
    }

    @Test @DisplayName("MySQL: comparison excludes NULL row like Postgres")
    void comparisonNull() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("amount", FieldType.INTEGER, RuleOperator.GT, "50"))),
            ctx(null, null, null))).containsExactly(3, 4);
    }

    @Test @DisplayName("MySQL: IS NULL")
    void isNull() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("amount", FieldType.INTEGER, RuleOperator.IS_NULL, ""))),
            ctx(null, null, null))).containsExactly(5);
    }

    @Test @DisplayName("MySQL: IN list")
    void inList() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("status", FieldType.STRING, RuleOperator.IN, "active"))),
            ctx(null, null, null))).containsExactly(1, 3);
    }

    @Test @DisplayName("MySQL: JSON path (string) meta.region")
    void jsonStringPath() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("meta.region", FieldType.STRING, RuleOperator.EQ, "west"))),
            ctx(null, null, null))).containsExactly(1, 3);
    }

    @Test @DisplayName("MySQL: JSON path (typed) meta.level >= 5")
    void jsonTypedPath() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("meta.level", FieldType.INTEGER, RuleOperator.GTE, "5"))),
            ctx(null, null, null))).containsExactly(2, 3);
    }

    @Test @DisplayName("MySQL: relationship EXISTS (membership)")
    void membershipExists() {
        assertThat(engineRowsMysql(List.of(relAllow(membership())), ctx("2", null, null)))
            .containsExactly(3, 4);
    }

    @Test @DisplayName("MySQL: DENY relationship = NOT EXISTS")
    void denyRelationship() {
        assertThat(engineRowsMysql(List.of(
            allow(new Rule("id", FieldType.INTEGER, RuleOperator.IS_NOT_NULL, "")),
            relDeny(membership())), ctx("2", null, null))).containsExactly(1, 2, 5);
    }

    @Test @DisplayName("MySQL: arbitrary custom claim binds into SQL")
    void customClaim() {
        assertThat(engineRowsMysql(List.of(allow(
            new Rule("tenant_txt", FieldType.STRING, RuleOperator.EQ, "{{region}}"))),
            ctx(null, null, "t1"))).containsExactly(1, 2);
    }

    private static List<Integer> likeRows(NamedParameterJdbcTemplate template, RuleOperator op, String pattern) {
        Policy policy = new Policy("p", "p", "like_t", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("val", FieldType.STRING, op, pattern)), List.of(Assignment.all()));
        SqlFilter f = new JdbcEvaluator(List.of(policy), List.of(), QuoteStyle.BACKTICK)
            .compile("like_t", ctx(null, null, null), Operation.SELECT);
        return template.queryForList("SELECT id FROM like_t WHERE " + f.sql() + " ORDER BY id",
            new MapSqlParameterSource(f.params()), Integer.class);
    }

    // Default collation is case-insensitive, so 'A%B' also matches; Postgres would keep only id 1.
    @Test @DisplayName("MySQL: LIKE with an escaped % matches the literal percent")
    void likeEscapedPercent() {
        assertThat(likeRows(jdbc, RuleOperator.LIKE, "a\\%b")).containsExactly(1, 5);
        assertThat(likeRows(jdbc, RuleOperator.NOT_LIKE, "a\\%b")).containsExactly(2, 3, 4);
    }

    @Test @DisplayName("MySQL: LIKE with an escaped _ and an escaped backslash")
    void likeEscapedUnderscoreAndBackslash() {
        assertThat(likeRows(jdbc, RuleOperator.LIKE, "a\\_b")).containsExactly(3);
        assertThat(likeRows(jdbc, RuleOperator.LIKE, "a\\\\b")).containsExactly(4);
    }

    @Test @DisplayName("MySQL under NO_BACKSLASH_ESCAPES: the escape still escapes")
    void likeEscapeSurvivesNoBackslashEscapes() {
        assertThat(likeRows(noBackslashEscapes, RuleOperator.LIKE, "a\\%b")).containsExactly(1, 5);
        assertThat(likeRows(noBackslashEscapes, RuleOperator.LIKE, "a\\_b")).containsExactly(3);
        assertThat(likeRows(noBackslashEscapes, RuleOperator.LIKE, "a\\\\b")).containsExactly(4);
    }

    @Test @DisplayName("LIKE pattern ending in the escape character is refused on every dialect")
    void trailingEscapeIsRefused() {
        for (QuoteStyle style : QuoteStyle.values()) {
            Policy policy = new Policy("p", "p", "like_t", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0,
                true, List.of(new Rule("val", FieldType.STRING, RuleOperator.LIKE, "abc\\")),
                List.of(Assignment.all()));
            JdbcEvaluator evaluator = new JdbcEvaluator(List.of(policy), List.of(), style);
            UserContext context = ctx(null, null, null);
            assertThatThrownBy(() -> evaluator.compile("like_t", context, Operation.SELECT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escape");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Policy allow(Rule... rules) {
        return new Policy("p", "p", "diff_t", PolicyEffect.ALLOW, Operation.ALL,
            LogicOperator.AND, 0, true, List.of(rules), List.of(Assignment.all()));
    }

    private static RelationPredicate membership() {
        return new RelationPredicate("diff_members", "member_tenant", "tenant_txt", LogicOperator.AND,
            List.of(new Rule("member_user", FieldType.STRING, RuleOperator.EQ, "{{currentUserId}}")));
    }

    private static Policy relAllow(RelationPredicate rel) {
        return new Policy("p", "p", "diff_t", PolicyEffect.ALLOW, Operation.ALL,
            LogicOperator.AND, 0, true, List.of(), List.of(rel), List.of(Assignment.all()));
    }

    private static Policy relDeny(RelationPredicate rel) {
        return new Policy("p", "p", "diff_t", PolicyEffect.DENY, Operation.ALL,
            LogicOperator.AND, 0, true, List.of(), List.of(rel), List.of(Assignment.all()));
    }

    private static UserContext ctx(String uid, String tid, String region) {
        return new UserContext() {
            @Override public String userId() { return uid; }
            @Override public String tenantId() { return tid; }
            @Override public Set<String> roles() { return Set.of("authenticated"); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) {
                return "region".equals(name) ? region : null;
            }
        };
    }
}
