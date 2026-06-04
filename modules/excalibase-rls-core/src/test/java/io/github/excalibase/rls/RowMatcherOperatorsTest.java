package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the comparison + negation + null + LIKE branches of RowMatcher that
 * the composition-focused tests don't exercise. Each test takes one operator
 * and asserts both matching and non-matching rows.
 */
class RowMatcherOperatorsTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static UserContext aliceCtx() {
        return new UserContext() {
            @Override public String userId() { return ALICE.toString(); }
            @Override public String tenantId() { return TENANT.toString(); }
            @Override public Set<String> roles() { return Set.of("authenticated"); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }

    @Test
    @DisplayName("NEQ matches when row value differs")
    void neq() {
        Policy p = allow(new Rule("status", FieldType.STRING, RuleOperator.NEQ, "archived"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("orders", Map.of("status", "active"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("orders", Map.of("status", "archived"), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("NOT_IN excludes the listed values")
    void notIn() {
        Policy p = allow(new Rule("status", FieldType.STRING, RuleOperator.NOT_IN, "archived,draft"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("orders", Map.of("status", "active"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("orders", Map.of("status", "draft"), aliceCtx(), Operation.SELECT)).isFalse();
        assertThat(m.matches("orders", Map.of("status", "archived"), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("GT / GTE comparisons over integers")
    void gt_gte_integer() {
        Policy gt = allow(new Rule("amount", FieldType.INTEGER, RuleOperator.GT, "100"));
        Policy gte = allow(new Rule("amount", FieldType.INTEGER, RuleOperator.GTE, "100"));
        RowMatcher mGt = new RowMatcher(List.of(gt));
        RowMatcher mGte = new RowMatcher(List.of(gte));
        assertThat(mGt.matches("orders", Map.of("amount", 100), aliceCtx(), Operation.SELECT)).isFalse();
        assertThat(mGt.matches("orders", Map.of("amount", 101), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(mGte.matches("orders", Map.of("amount", 100), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(mGte.matches("orders", Map.of("amount", 99), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("LT / LTE comparisons over longs")
    void lt_lte_long() {
        Policy lt = allow(new Rule("size", FieldType.LONG, RuleOperator.LT, "1024"));
        Policy lte = allow(new Rule("size", FieldType.LONG, RuleOperator.LTE, "1024"));
        RowMatcher mLt = new RowMatcher(List.of(lt));
        RowMatcher mLte = new RowMatcher(List.of(lte));
        assertThat(mLt.matches("files", Map.of("size", 1024L), aliceCtx(), Operation.SELECT)).isFalse();
        assertThat(mLt.matches("files", Map.of("size", 1023L), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(mLte.matches("files", Map.of("size", 1024L), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(mLte.matches("files", Map.of("size", 1025L), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("LIKE matches SQL-style wildcards (% and _)")
    void like_wildcards() {
        Policy p = allow(new Rule("path", FieldType.STRING, RuleOperator.LIKE, "/users/%"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("files", Map.of("path", "/users/alice/notes.txt"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("files", Map.of("path", "/admin/audit.log"), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("NOT_LIKE inverts the LIKE match")
    void notLike() {
        Policy p = allow(new Rule("path", FieldType.STRING, RuleOperator.NOT_LIKE, "/admin/%"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("files", Map.of("path", "/users/alice/notes.txt"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("files", Map.of("path", "/admin/audit.log"), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("IS_NOT_NULL passes only when the field is present and non-null")
    void isNotNull() {
        Policy p = allow(new Rule("verified_at", FieldType.DATETIME, RuleOperator.IS_NOT_NULL, null));
        RowMatcher m = new RowMatcher(List.of(p));
        java.util.Map<String, Object> verified = java.util.Map.of("verified_at", "2026-01-01T00:00:00");
        java.util.Map<String, Object> unverified = new java.util.HashMap<>();
        unverified.put("verified_at", null);
        assertThat(m.matches("users", verified, aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("users", unverified, aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("comparison against DATETIME row values uses {{daysAgo:N}} on the right")
    void datetime_daysAgo() {
        Policy p = allow(new Rule("created_at", FieldType.DATETIME, RuleOperator.GT, "{{daysAgo:7}}"));
        RowMatcher m = new RowMatcher(List.of(p));
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(m.matches("posts", Map.of("created_at", recent.toString()), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("posts", Map.of("created_at", old.toString()), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("BOOLEAN row coercion: string \"true\"/\"false\" treated as the literal boolean")
    void boolean_coercion() {
        Policy p = allow(new Rule("active", FieldType.BOOLEAN, RuleOperator.EQ, "true"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("users", Map.of("active", true), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("users", Map.of("active", "true"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("users", Map.of("active", false), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("DOUBLE row coercion")
    void double_coercion() {
        Policy p = allow(new Rule("score", FieldType.DOUBLE, RuleOperator.GTE, "0.5"));
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("entries", Map.of("score", 0.5), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("entries", Map.of("score", "0.9"), aliceCtx(), Operation.SELECT)).isTrue();
        assertThat(m.matches("entries", Map.of("score", 0.4), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("disabled policy is skipped entirely — does not turn RLS on")
    void disabledPolicy_skipped() {
        Policy ownerAllow = new Policy(
            UUID.randomUUID().toString(), "owner", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, /*enabled*/ false,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.all())
        );
        RowMatcher m = new RowMatcher(List.of(ownerAllow));
        // Disabled → no enabled ALLOW exists for "orders" → RLS off → unrestricted.
        assertThat(m.matches("orders", Map.of("user_id", UUID.randomUUID().toString()),
            aliceCtx(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("policy assigned to USER: target user gets policy, other users default-deny")
    void userAssignment() {
        Policy p = new Policy(
            UUID.randomUUID().toString(), "alice-specific", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
            List.of(Assignment.user(ALICE.toString()))
        );
        RowMatcher m = new RowMatcher(List.of(p));
        // Alice: assignment matches → policy in scope → ALLOW matches her row.
        assertThat(m.matches("orders", Map.of("user_id", ALICE.toString()),
            aliceCtx(), Operation.SELECT)).isTrue();
        // Bob: assignment doesn't match → no in-scope ALLOW → default-deny.
        UserContext bob = new UserContext() {
            @Override public String userId() { return UUID.randomUUID().toString(); }
            @Override public String tenantId() { return TENANT.toString(); }
            @Override public Set<String> roles() { return Set.of("authenticated"); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
        assertThat(m.matches("orders", Map.of("user_id", ALICE.toString()),
            bob, Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("policy assigned to GROUP: in-group gets policy, out-of-group default-deny")
    void groupAssignment() {
        Policy p = new Policy(
            UUID.randomUUID().toString(), "group-only", "docs",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("group_id", FieldType.STRING, RuleOperator.EQ, "acme")),
            List.of(Assignment.group("admins"))
        );
        RowMatcher m = new RowMatcher(List.of(p));
        UserContext inGroup = new UserContext() {
            @Override public String userId() { return ALICE.toString(); }
            @Override public String tenantId() { return TENANT.toString(); }
            @Override public Set<String> roles() { return Set.of("authenticated"); }
            @Override public Set<String> groupIds() { return Set.of("admins"); }
        };
        UserContext outOfGroup = new UserContext() {
            @Override public String userId() { return ALICE.toString(); }
            @Override public String tenantId() { return TENANT.toString(); }
            @Override public Set<String> roles() { return Set.of("authenticated"); }
            @Override public Set<String> groupIds() { return Set.of("users"); }
        };
        assertThat(m.matches("docs", Map.of("group_id", "acme"), inGroup, Operation.SELECT)).isTrue();
        assertThat(m.matches("docs", Map.of("group_id", "other"), inGroup, Operation.SELECT)).isFalse();
        // Out of group → no in-scope ALLOW (policy exists for "docs" so RLS is ON) → default-deny.
        assertThat(m.matches("docs", Map.of("group_id", "other"), outOfGroup, Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("policy with OR rule logic matches if any rule matches")
    void orRuleLogic() {
        Policy p = new Policy(
            UUID.randomUUID().toString(), "owner or public", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.OR, 0, true,
            List.of(
                new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}"),
                new Rule("public", FieldType.BOOLEAN, RuleOperator.EQ, "true")
            ),
            List.of(Assignment.all())
        );
        RowMatcher m = new RowMatcher(List.of(p));
        UUID someoneElse = UUID.randomUUID();
        // owner branch
        assertThat(m.matches("orders",
            Map.of("user_id", ALICE.toString(), "public", false), aliceCtx(), Operation.SELECT)).isTrue();
        // public branch
        assertThat(m.matches("orders",
            Map.of("user_id", someoneElse.toString(), "public", true), aliceCtx(), Operation.SELECT)).isTrue();
        // neither
        assertThat(m.matches("orders",
            Map.of("user_id", someoneElse.toString(), "public", false), aliceCtx(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("policy with no rules matches every row by default")
    void emptyRules_matchEverything() {
        Policy p = new Policy(
            UUID.randomUUID().toString(), "empty", "orders",
            PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(), List.of(Assignment.all())
        );
        RowMatcher m = new RowMatcher(List.of(p));
        assertThat(m.matches("orders", Map.of("id", 1), aliceCtx(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("nested-path traversal returns null for missing intermediate keys")
    void nestedPath_missingKey_returnsNull() {
        Policy p = allow(new Rule("dept.tenant_id", FieldType.UUID, RuleOperator.IS_NULL, null));
        RowMatcher m = new RowMatcher(List.of(p));
        // No "dept" key → readPath returns null → IS_NULL passes.
        assertThat(m.matches("employees", Map.of("id", 1), aliceCtx(), Operation.SELECT)).isTrue();
    }

    private static Policy allow(Rule rule) {
        return new Policy(
            UUID.randomUUID().toString(), "p", policyResourceFor(rule), PolicyEffect.ALLOW,
            Operation.ALL, LogicOperator.AND, 0, true, List.of(rule), List.of(Assignment.all())
        );
    }

    /** Pick the policy resource from the field path, so each test's policy and dispatch line up. */
    private static String policyResourceFor(Rule rule) {
        // The tests below pair each rule with a specific resource — keep them in sync.
        return switch (rule.field()) {
            case "status" -> "orders";
            case "amount" -> "orders";
            case "size" -> "files";
            case "path" -> "files";
            case "verified_at" -> "users";
            case "created_at" -> "posts";
            case "active" -> "users";
            case "score" -> "entries";
            case "dept.tenant_id" -> "employees";
            default -> "orders";
        };
    }
}
