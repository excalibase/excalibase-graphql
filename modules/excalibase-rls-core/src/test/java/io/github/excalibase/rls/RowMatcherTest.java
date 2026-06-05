package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RowMatcherTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID BOB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final UserContext alice = userContext(ALICE, TENANT, Set.of("authenticated"));

    @Nested
    @DisplayName("composition rule")
    class Composition {

        @Test
        @DisplayName("no policies for resource → row is visible (unrestricted, opt-in semantics)")
        void noPolicies_visible() {
            RowMatcher matcher = new RowMatcher(List.of());
            assertThat(matcher.matches("orders", Map.of("id", 1), alice, Operation.SELECT)).isTrue();
        }

        @Test
        @DisplayName("ALLOW that matches → visible")
        void allowMatches_visible() {
            Policy ownerAllow = ownerAllowPolicy();
            RowMatcher matcher = new RowMatcher(List.of(ownerAllow));
            assertThat(matcher.matches("orders", Map.of("user_id", ALICE.toString()), alice, Operation.SELECT)).isTrue();
        }

        @Test
        @DisplayName("ALLOW that does NOT match → hidden (default-deny once any ALLOW exists)")
        void allowExistsButDoesntMatch_hidden() {
            Policy ownerAllow = ownerAllowPolicy();
            RowMatcher matcher = new RowMatcher(List.of(ownerAllow));
            // Alice asking about Bob's order — owner policy says user_id must equal currentUserId
            assertThat(matcher.matches("orders", Map.of("user_id", BOB.toString()), alice, Operation.SELECT)).isFalse();
        }

        @Test
        @DisplayName("two ALLOW policies: any match → visible (ALLOW union)")
        void allowUnion_anyMatch_visible() {
            Policy ownerAllow = ownerAllowPolicy();
            Policy publicAllow = allowPolicy("public read",
                new Rule("is_public", FieldType.BOOLEAN, RuleOperator.EQ, "true"));
            RowMatcher matcher = new RowMatcher(List.of(ownerAllow, publicAllow));
            // Alice asking about Bob's order, but it's public
            assertThat(matcher.matches("orders",
                Map.of("user_id", BOB.toString(), "is_public", true), alice, Operation.SELECT)).isTrue();
        }

        @Test
        @DisplayName("DENY that matches → hidden even if ALLOW matches (DENY veto)")
        void denyVetoesAllow() {
            Policy ownerAllow = ownerAllowPolicy();
            Policy hideDrafts = denyPolicy("hide drafts",
                new Rule("status", FieldType.STRING, RuleOperator.EQ, "draft"));
            RowMatcher matcher = new RowMatcher(List.of(ownerAllow, hideDrafts));
            assertThat(matcher.matches("orders",
                Map.of("user_id", ALICE.toString(), "status", "draft"), alice, Operation.SELECT)).isFalse();
            assertThat(matcher.matches("orders",
                Map.of("user_id", ALICE.toString(), "status", "submitted"), alice, Operation.SELECT)).isTrue();
        }

        @Test
        @DisplayName("DENY alone cannot make a row visible (DENY is subtractive)")
        void denyAlone_cannotShow_butHidesNothing() {
            // Only a DENY policy exists. The composition rule: no ALLOW → unrestricted.
            // DENY still applies subtractively.
            Policy hideDrafts = denyPolicy("hide drafts", "posts",
                new Rule("status", FieldType.STRING, RuleOperator.EQ, "draft"));
            RowMatcher matcher = new RowMatcher(List.of(hideDrafts));
            assertThat(matcher.matches("posts",
                Map.of("status", "draft"), alice, Operation.SELECT)).isFalse();
            assertThat(matcher.matches("posts",
                Map.of("status", "published"), alice, Operation.SELECT)).isTrue();
        }
    }

    @Nested
    @DisplayName("operation scoping")
    class OperationScoping {

        @Test
        @DisplayName("policy applies only to declared operations")
        void operationFilter_excludesUnlistedOps() {
            // ALLOW policy that only governs SELECT
            Policy selectOnlyAllow = policyBuilder()
                .effect(PolicyEffect.ALLOW)
                .operations(Set.of(Operation.SELECT))
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.all())
                .build();
            RowMatcher matcher = new RowMatcher(List.of(selectOnlyAllow));

            Map<String, Object> row = Map.of("user_id", ALICE.toString());

            // SELECT: policy in scope, ALLOW matches → visible
            assertThat(matcher.matches("orders", row, alice, Operation.SELECT)).isTrue();
            // INSERT: policy NOT in scope (no policies for INSERT at all) → unrestricted → allowed
            assertThat(matcher.matches("orders", row, alice, Operation.INSERT)).isTrue();
        }
    }

    @Nested
    @DisplayName("assignment scoping")
    class AssignmentScoping {

        @Test
        @DisplayName("policy assigned to ROLE: admin sees their rows; other roles default-deny")
        void roleAssignment_appliesOnlyToMatchingRole() {
            UserContext admin = userContext(ALICE, TENANT, Set.of("admin"));
            UserContext regular = userContext(ALICE, TENANT, Set.of("authenticated"));

            Policy adminAllow = policyBuilder()
                .effect(PolicyEffect.ALLOW)
                .rules(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"))
                .assignments(Assignment.role("admin"))
                .build();
            RowMatcher matcher = new RowMatcher(List.of(adminAllow));

            // admin: policy applies; matches → visible
            assertThat(matcher.matches("orders",
                Map.of("user_id", ALICE.toString()), admin, Operation.SELECT)).isTrue();
            // regular: an ALLOW exists for "orders" so RLS is ON; nothing in scope
            // for this subscriber → default-deny.
            assertThat(matcher.matches("orders",
                Map.of("user_id", ALICE.toString()), regular, Operation.SELECT)).isFalse();
            assertThat(matcher.matches("orders",
                Map.of("user_id", BOB.toString()), regular, Operation.SELECT)).isFalse();
        }
    }

    @Nested
    @DisplayName("rule operators")
    class RuleOperators {

        @Test
        @DisplayName("EQ + UUID coerces row string to UUID before compare")
        void eq_uuid_coerces() {
            Policy p = allowPolicy("uuid eq",
                new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"));
            RowMatcher matcher = new RowMatcher(List.of(p));
            // Row has the UUID as String; the matcher coerces both sides.
            assertThat(matcher.matches("orders",
                Map.of("user_id", ALICE.toString()), alice, Operation.SELECT)).isTrue();
        }

        @Test
        @DisplayName("IN matches if row value is in the set")
        void in_setMembership() {
            Policy p = allowPolicy("status in",
                new Rule("status", FieldType.STRING, RuleOperator.IN, "active,pending"));
            RowMatcher matcher = new RowMatcher(List.of(p));
            assertThat(matcher.matches("orders", Map.of("status", "active"), alice, Operation.SELECT)).isTrue();
            assertThat(matcher.matches("orders", Map.of("status", "pending"), alice, Operation.SELECT)).isTrue();
            assertThat(matcher.matches("orders", Map.of("status", "archived"), alice, Operation.SELECT)).isFalse();
        }

        @Test
        @DisplayName("IN with {{currentUserGroupIds}} dynamically resolves the set")
        void in_withVariable_resolvesToSet() {
            UserContext withGroups = userContext(ALICE, TENANT, Set.of("authenticated"), Set.of("g1", "g2"));
            Policy p = allowPolicy("group membership", "items",
                new Rule("group_id", FieldType.STRING, RuleOperator.IN, "{{currentUserGroupIds}}"));
            RowMatcher matcher = new RowMatcher(List.of(p));
            assertThat(matcher.matches("items", Map.of("group_id", "g1"), withGroups, Operation.SELECT)).isTrue();
            assertThat(matcher.matches("items", Map.of("group_id", "g3"), withGroups, Operation.SELECT)).isFalse();
        }

        @Test
        @DisplayName("IS_NULL / IS_NOT_NULL respect Map null values")
        void isNull_isNotNull() {
            Policy nullPolicy = allowPolicy("archived null",
                new Rule("archived_at", FieldType.DATETIME, RuleOperator.IS_NULL, null));
            RowMatcher matcher = new RowMatcher(List.of(nullPolicy));
            Map<String, Object> rowNull = new java.util.HashMap<>();
            rowNull.put("archived_at", null);
            Map<String, Object> rowSet = Map.of("archived_at", "2026-01-01T00:00:00");
            assertThat(matcher.matches("orders", rowNull, alice, Operation.SELECT)).isTrue();
            assertThat(matcher.matches("orders", rowSet, alice, Operation.SELECT)).isFalse();
        }
    }

    @Nested
    @DisplayName("nested field paths")
    class NestedPaths {

        @Test
        @DisplayName("dotted field path traverses nested Map")
        void dottedPath_resolvesFromNestedMap() {
            Policy p = allowPolicy("department tenant",
                new Rule("department.tenant_id", FieldType.UUID, RuleOperator.EQ, "{{currentTenantId}}"));
            RowMatcher matcher = new RowMatcher(List.of(p));
            Map<String, Object> row = Map.of(
                "id", 5,
                "department", Map.of("tenant_id", TENANT.toString())
            );
            assertThat(matcher.matches("employees", row, alice, Operation.SELECT)).isTrue();
        }
    }

    // ---------- helpers ----------

    private static UserContext userContext(UUID userId, UUID tenantId, Set<String> roles) {
        return userContext(userId, tenantId, roles, Set.of());
    }

    private static UserContext userContext(UUID userId, UUID tenantId, Set<String> roles, Set<String> groups) {
        return new UserContext() {
            @Override public String userId() { return userId.toString(); }
            @Override public String tenantId() { return tenantId.toString(); }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return groups; }
        };
    }

    private static Policy ownerAllowPolicy() {
        return allowPolicy("owner",
            new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"));
    }

    private static Policy allowPolicy(String name, Rule rule) {
        return allowPolicy(name, "orders", rule);
    }

    private static Policy allowPolicy(String name, String resource, Rule rule) {
        return policyBuilder()
            .name(name)
            .resource(resource)
            .effect(PolicyEffect.ALLOW)
            .rules(rule)
            .assignments(Assignment.all())
            .build();
    }

    private static Policy denyPolicy(String name, Rule rule) {
        return denyPolicy(name, "orders", rule);
    }

    private static Policy denyPolicy(String name, String resource, Rule rule) {
        return policyBuilder()
            .name(name)
            .resource(resource)
            .effect(PolicyEffect.DENY)
            .rules(rule)
            .assignments(Assignment.all())
            .build();
    }

    private static PolicyBuilder policyBuilder() {
        return new PolicyBuilder();
    }

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
        PolicyBuilder operations(Set<Operation> ops) { this.operations = ops; return this; }
        PolicyBuilder rules(Rule... rs) { this.rules = List.of(rs); return this; }
        PolicyBuilder assignments(Assignment... as) { this.assignments = List.of(as); return this; }

        Policy build() {
            return new Policy(id, name, resource, effect, operations, ruleLogic, priority, enabled, rules, assignments);
        }
    }
}
