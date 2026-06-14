package io.github.excalibase.rls.jdbc;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests that assert the exact SQL fragment + bind-param map a
 * {@link JdbcEvaluator} produces for a given (policies, user, operation,
 * resource) tuple. Integration with a live database is in
 * {@link JdbcEvaluatorIntegrationTest}; here we only check the compiled form.
 */
class JdbcEvaluatorTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static UserContext aliceAuth() {
        return ctx(ALICE.toString(), TENANT.toString(), Set.of("authenticated"), Set.of());
    }

    private static UserContext anon() {
        return ctx(null, null, Set.of("anon"), Set.of());
    }

    private static UserContext ctx(String uid, String tid, Set<String> roles, Set<String> groups) {
        return new UserContext() {
            @Override public String userId() { return uid; }
            @Override public String tenantId() { return tid; }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return groups; }
        };
    }

    @Nested
    @DisplayName("composition outputs")
    class Composition {

        @Test
        @DisplayName("no policies for resource → UNRESTRICTED (empty sql, empty params)")
        void noPolicies_unrestricted() {
            JdbcEvaluator e = new JdbcEvaluator(List.of());
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f).isEqualTo(SqlFilter.UNRESTRICTED);
        }

        @Test
        @DisplayName("ALLOW exists but not in scope for user → DENY_ALL (1=0, empty params)")
        void allowExistsButNotInScope_denyAll() {
            Policy adminOnly = policyBuilder()
                .name("admin-only").effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.role("admin"))
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(adminOnly));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("1=0");
            assertThat(f.params()).isEmpty();
        }

        @Test
        @DisplayName("anon on a resource with any ALLOW gets DENY_ALL")
        void anon_defaultDeny() {
            Policy authOnly = policyBuilder()
                .name("auth-owner").effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.role("authenticated"))
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(authOnly));
            SqlFilter f = e.compile("orders", anon(), Operation.SELECT);
            assertThat(f).isEqualTo(SqlFilter.DENY_ALL);
        }

        @Test
        @DisplayName("two ALLOW policies in scope are OR-joined")
        void twoAllows_orJoined() {
            Policy owner = policyBuilder().name("owner").effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all())
                .build();
            Policy pub = policyBuilder().name("public").effect(PolicyEffect.ALLOW)
                .rules(new Rule("is_public", FieldType.BOOLEAN, RuleOperator.EQ, "true"))
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(owner, pub));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql())
                .contains(" OR ")
                .contains("\"user_id\" = :")
                .contains("\"is_public\" = :");
            assertThat(f.params()).hasSize(2);
        }

        @Test
        @DisplayName("DENY composes as NOT(OR(denies)) AND'd with allow tree")
        void denyVetoes() {
            Policy owner = policyBuilder().name("owner").effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all())
                .build();
            Policy hideDrafts = policyBuilder().name("hide-drafts").effect(PolicyEffect.DENY)
                .rules(new Rule("status", FieldType.STRING, RuleOperator.EQ, "draft"))
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(owner, hideDrafts));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql())
                .contains("\"user_id\" = :")
                .contains(" AND NOT (")
                .contains("\"status\" = :");
        }

        @Test
        @DisplayName("DENY without ALLOW (RLS off) still emits the NOT(OR(denies)) clause")
        void denyOnly_subtractsButRlsOff() {
            Policy hideDrafts = policyBuilder().name("hide-drafts").resource("posts").effect(PolicyEffect.DENY)
                .rules(new Rule("status", FieldType.STRING, RuleOperator.EQ, "draft"))
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(hideDrafts));
            SqlFilter f = e.compile("posts", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).startsWith("NOT (").contains("\"status\" = :");
        }
    }

    @Nested
    @DisplayName("operators")
    class Operators {

        @Test
        @DisplayName("EQ with literal value")
        void eq_literal() {
            JdbcEvaluator e = single("orders", new Rule("status", FieldType.STRING, RuleOperator.EQ, "active"));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("\"status\" = :rls_p0");
            assertThat(f.params()).containsEntry("rls_p0", "active");
        }

        @Test
        @DisplayName("EQ with {{currentUserId}} resolved to UUID")
        void eq_variable_resolved() {
            JdbcEvaluator e = single("orders", new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("\"user_id\" = :rls_p0");
            assertThat(f.params()).containsEntry("rls_p0", ALICE);
        }

        @Test @DisplayName("NEQ → <>")
        void neq() {
            JdbcEvaluator e = single("orders", new Rule("status", FieldType.STRING, RuleOperator.NEQ, "archived"));
            assertThat(e.compile("orders", aliceAuth(), Operation.SELECT).sql())
                .isEqualTo("\"status\" <> :rls_p0");
        }

        @Test @DisplayName("GT / GTE / LT / LTE → > >= < <=")
        void comparisons() {
            assertThat(single("o", new Rule("amount", FieldType.INTEGER, RuleOperator.GT, "100"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"amount\" > :rls_p0");
            assertThat(single("o", new Rule("amount", FieldType.INTEGER, RuleOperator.GTE, "100"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"amount\" >= :rls_p0");
            assertThat(single("o", new Rule("amount", FieldType.INTEGER, RuleOperator.LT, "100"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"amount\" < :rls_p0");
            assertThat(single("o", new Rule("amount", FieldType.INTEGER, RuleOperator.LTE, "100"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"amount\" <= :rls_p0");
        }

        @Test
        @DisplayName("IN expands to (?, ?, ?) with one param per value")
        void in_expandsParams() {
            JdbcEvaluator e = single("orders",
                new Rule("status", FieldType.STRING, RuleOperator.IN, "active,paid,shipped"));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("\"status\" IN (:rls_p0_0, :rls_p0_1, :rls_p0_2)");
            assertThat(f.params())
                .containsEntry("rls_p0_0", "active")
                .containsEntry("rls_p0_1", "paid")
                .containsEntry("rls_p0_2", "shipped");
        }

        @Test
        @DisplayName("IN with empty resolved list → 1=0 (matches nothing)")
        void in_emptyList_matchesNothing() {
            UserContext noGroups = ctx(ALICE.toString(), TENANT.toString(),
                Set.of("authenticated"), Set.of());
            JdbcEvaluator e = single("orders",
                new Rule("group_id", FieldType.STRING, RuleOperator.IN, "{{currentUserGroupIds}}"));
            SqlFilter f = e.compile("orders", noGroups, Operation.SELECT);
            assertThat(f.sql()).isEqualTo("1=0");
        }

        @Test
        @DisplayName("NOT_IN expands as NOT IN; empty list → 1=1 (matches all)")
        void notIn() {
            JdbcEvaluator e = single("orders",
                new Rule("status", FieldType.STRING, RuleOperator.NOT_IN, "draft,trash"));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("\"status\" NOT IN (:rls_p0_0, :rls_p0_1)");

            UserContext noGroups = ctx(ALICE.toString(), TENANT.toString(),
                Set.of("authenticated"), Set.of());
            JdbcEvaluator emptyVar = single("orders",
                new Rule("group_id", FieldType.STRING, RuleOperator.NOT_IN, "{{currentUserGroupIds}}"));
            assertThat(emptyVar.compile("orders", noGroups, Operation.SELECT).sql()).isEqualTo("1=1");
        }

        @Test @DisplayName("LIKE / NOT_LIKE pass the pattern through unchanged (caller's responsibility)")
        void likeAndNotLike() {
            assertThat(single("o", new Rule("path", FieldType.STRING, RuleOperator.LIKE, "/users/%"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"path\" LIKE :rls_p0");
            assertThat(single("o", new Rule("path", FieldType.STRING, RuleOperator.NOT_LIKE, "/admin/%"))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"path\" NOT LIKE :rls_p0");
        }

        @Test @DisplayName("IS_NULL / IS_NOT_NULL emit no param")
        void nullOps() {
            JdbcEvaluator e = single("o", new Rule("archived_at", FieldType.DATETIME, RuleOperator.IS_NULL, null));
            SqlFilter f = e.compile("o", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).isEqualTo("\"archived_at\" IS NULL");
            assertThat(f.params()).isEmpty();
            assertThat(single("o", new Rule("verified_at", FieldType.DATETIME, RuleOperator.IS_NOT_NULL, null))
                .compile("o", aliceAuth(), Operation.SELECT).sql()).isEqualTo("\"verified_at\" IS NOT NULL");
        }
    }

    @Nested
    @DisplayName("policy-level structure")
    class PolicyStructure {

        @Test
        @DisplayName("two rules under AND wrap in parens with AND")
        void ruleLogic_and() {
            Policy p = policyBuilder().name("tenant+owner").effect(PolicyEffect.ALLOW)
                .ruleLogic(LogicOperator.AND)
                .rules(
                    new Rule("tenant_id", FieldType.UUID, RuleOperator.EQ, "{{currentTenantId}}"),
                    new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")
                )
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(p));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql())
                .contains("\"tenant_id\" = :")
                .contains(" AND ")
                .contains("\"user_id\" = :");
        }

        @Test
        @DisplayName("two rules under OR wrap in parens with OR")
        void ruleLogic_or() {
            Policy p = policyBuilder().name("owner-or-public").effect(PolicyEffect.ALLOW)
                .ruleLogic(LogicOperator.OR)
                .rules(
                    new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"),
                    new Rule("is_public", FieldType.BOOLEAN, RuleOperator.EQ, "true")
                )
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(p));
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql())
                .contains("\"user_id\" = :")
                .contains(" OR ")
                .contains("\"is_public\" = :");
        }

        @Test
        @DisplayName("disabled policy is not emitted")
        void disabledPolicy_skipped() {
            Policy disabled = policyBuilder().name("owner").effect(PolicyEffect.ALLOW)
                .enabled(false)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(disabled));
            assertThat(e.compile("orders", aliceAuth(), Operation.SELECT)).isEqualTo(SqlFilter.UNRESTRICTED);
        }

        @Test
        @DisplayName("policy targeting a different operation is not emitted (INSERT-only policy on SELECT path)")
        void operationScoping() {
            Policy insertOnly = policyBuilder().name("insert-only").effect(PolicyEffect.ALLOW)
                .operations(Set.of(Operation.INSERT))
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all())
                .build();
            JdbcEvaluator e = new JdbcEvaluator(List.of(insertOnly));
            // SELECT path: no ALLOW for SELECT → unrestricted
            assertThat(e.compile("orders", aliceAuth(), Operation.SELECT)).isEqualTo(SqlFilter.UNRESTRICTED);
            // INSERT path: ALLOW exists → ALLOW emitted
            SqlFilter f = e.compile("orders", aliceAuth(), Operation.INSERT);
            assertThat(f.sql()).contains("\"user_id\" = :");
        }
    }

    @Nested
    @DisplayName("param namespacing")
    class ParamNamespacing {

        @Test
        @DisplayName("multiple rules in one policy get distinct param keys")
        void distinctKeys_perRule() {
            Policy p = policyBuilder().name("tenant+owner").effect(PolicyEffect.ALLOW)
                .ruleLogic(LogicOperator.AND)
                .rules(
                    new Rule("tenant_id", FieldType.UUID, RuleOperator.EQ, "{{currentTenantId}}"),
                    new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")
                )
                .assignments(Assignment.all())
                .build();
            SqlFilter f = new JdbcEvaluator(List.of(p))
                .compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.params()).hasSize(2);
            assertThat(f.params().keySet()).allSatisfy(k -> assertThat(k).startsWith("rls_p"));
        }

        @Test
        @DisplayName("params from two policies do not collide")
        void noCollision_acrossPolicies() {
            Policy a = policyBuilder().name("a").effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all()).build();
            Policy b = policyBuilder().name("b").effect(PolicyEffect.ALLOW)
                .rules(new Rule("tenant_id", FieldType.UUID, RuleOperator.EQ, "{{currentTenantId}}"))
                .assignments(Assignment.all()).build();
            SqlFilter f = new JdbcEvaluator(List.of(a, b))
                .compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.params()).hasSize(2);
            assertThat(f.params().keySet()).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("constructor accepts null list")
        void nullPolicies_treatedAsEmpty() {
            JdbcEvaluator e = new JdbcEvaluator(null);
            assertThat(e.compile("orders", aliceAuth(), Operation.SELECT)).isEqualTo(SqlFilter.UNRESTRICTED);
        }
    }

    // ---------- helpers ----------

    private static JdbcEvaluator single(String resource, Rule rule) {
        Policy p = policyBuilder().name("p").effect(PolicyEffect.ALLOW)
            .resource(resource).rules(rule).assignments(Assignment.all()).build();
        return new JdbcEvaluator(List.of(p));
    }

    private static PolicyBuilder policyBuilder() { return new PolicyBuilder(); }

    private static final class PolicyBuilder {
        private String id = UUID.randomUUID().toString();
        private String name = "p";
        private String resource = "orders";
        private PolicyEffect effect = PolicyEffect.ALLOW;
        private Set<Operation> operations = Operation.ALL;
        private LogicOperator ruleLogic = LogicOperator.AND;
        private int priority = 0;
        private boolean enabled = true;
        private List<Rule> rules = List.of();
        private List<Assignment> assignments = List.of(Assignment.all());

        PolicyBuilder name(String n) { this.name = n; return this; }
        PolicyBuilder resource(String r) { this.resource = r; return this; }
        PolicyBuilder effect(PolicyEffect e) { this.effect = e; return this; }
        PolicyBuilder operations(Set<Operation> o) { this.operations = o; return this; }
        PolicyBuilder ruleLogic(LogicOperator l) { this.ruleLogic = l; return this; }
        PolicyBuilder enabled(boolean e) { this.enabled = e; return this; }
        PolicyBuilder rules(Rule... rs) { this.rules = List.of(rs); return this; }
        PolicyBuilder assignments(Assignment... as) { this.assignments = List.of(as); return this; }
        Policy build() {
            return new Policy(id, name, resource, effect, operations, ruleLogic, priority, enabled, rules, assignments);
        }
    }

    @Nested
    @DisplayName("identifier quoting dialect")
    class Quoting {

        private final Rule ownerRule = new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}");

        @Test
        @DisplayName("ANSI (default) quotes identifiers with double quotes — Postgres")
        void ansiDoubleQuotes() {
            SqlFilter f = single("orders", ownerRule).compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).contains("\"user_id\"").doesNotContain("`user_id`");
        }

        @Test
        @DisplayName("BACKTICK quotes identifiers — MySQL")
        void backtickForMysql() {
            SqlFilter f = new JdbcEvaluator(List.of(policyBuilder().rules(ownerRule)
                    .assignments(Assignment.all()).build()), List.of(), QuoteStyle.BACKTICK)
                    .compile("orders", aliceAuth(), Operation.SELECT);
            assertThat(f.sql()).contains("`user_id`").doesNotContain("\"user_id\"");
        }
    }
}
