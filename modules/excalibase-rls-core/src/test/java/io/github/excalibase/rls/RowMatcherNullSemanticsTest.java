package io.github.excalibase.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The in-memory matcher follows SQL three-valued logic: a comparison with NULL is
 * unknown, never true, so a row is kept only when the composed predicate is TRUE —
 * exactly what the emitted SQL and native Postgres do. A column absent from the
 * image (a key-only DELETE, an unchanged TOAST value) is unknown too.
 */
class RowMatcherNullSemanticsTest {

    private static final String ALICE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    @DisplayName("anonymous caller does not match a row whose owner is NULL")
    void anonymous_eq_nullOwner_excluded() {
        RowMatcher matcher = new RowMatcher(List.of(allow(eq("owner_id", "{{currentUserId}}"))));
        assertThat(matcher.matches("t", row("owner_id", null), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("anonymous caller does not match an owned row either")
    void anonymous_eq_ownedRow_excluded() {
        RowMatcher matcher = new RowMatcher(List.of(allow(eq("owner_id", "{{currentUserId}}"))));
        assertThat(matcher.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("NEQ against a NULL row value is unknown, so the row is excluded")
    void neq_nullRow_excluded() {
        RowMatcher matcher = new RowMatcher(List.of(allow(rule("status", RuleOperator.NEQ, "archived"))));
        assertThat(matcher.matches("t", row("status", null), alice(), Operation.SELECT)).isFalse();
        assertThat(matcher.matches("t", row("status", "active"), alice(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("NEQ against an anonymous caller's id is unknown, so every row is excluded")
    void neq_anonymous_excluded() {
        RowMatcher matcher = new RowMatcher(List.of(allow(rule("owner_id", RuleOperator.NEQ, "{{currentUserId}}"))));
        assertThat(matcher.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("NOT_IN with a NULL row value is unknown, so the row is excluded")
    void notIn_nullRow_excluded() {
        RowMatcher matcher = new RowMatcher(List.of(allow(rule("status", RuleOperator.NOT_IN, "archived,deleted"))));
        assertThat(matcher.matches("t", row("status", null), alice(), Operation.SELECT)).isFalse();
        assertThat(matcher.matches("t", row("status", "active"), alice(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("IN and NOT_IN against an anonymous caller's id exclude the row and do not throw")
    void in_anonymous_excluded() {
        RowMatcher in = new RowMatcher(List.of(allow(rule("owner_id", RuleOperator.IN, "{{currentUserId}}"))));
        RowMatcher notIn = new RowMatcher(List.of(allow(rule("owner_id", RuleOperator.NOT_IN, "{{currentUserId}}"))));
        assertThatCode(() -> in.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).doesNotThrowAnyException();
        assertThat(in.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).isFalse();
        assertThat(notIn.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("NOT_LIKE with a NULL row value or a NULL pattern excludes the row")
    void notLike_null_excluded() {
        RowMatcher literal = new RowMatcher(List.of(allow(rule("status", RuleOperator.NOT_LIKE, "arch%"))));
        RowMatcher anonPattern = new RowMatcher(List.of(allow(rule("owner_id", RuleOperator.NOT_LIKE, "{{currentUserId}}"))));
        assertThat(literal.matches("t", row("status", null), alice(), Operation.SELECT)).isFalse();
        assertThat(literal.matches("t", row("status", "active"), alice(), Operation.SELECT)).isTrue();
        assertThat(anonPattern.matches("t", row("owner_id", ALICE), anon(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("a DENY whose predicate is unknown hides the row, as NOT (NULL) does in SQL")
    void deny_unknown_hidesRow() {
        RowMatcher matcher = new RowMatcher(List.of(
            allow(rule("id", RuleOperator.IS_NOT_NULL, null)),
            deny(rule("status", RuleOperator.EQ, "archived"))));
        assertThat(matcher.matches("t", row("id", 1, "status", null), alice(), Operation.SELECT)).isFalse();
        assertThat(matcher.matches("t", row("id", 1, "status", "active"), alice(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("a DENY-only table hides a row whose DENY column is NULL")
    void denyOnly_unknown_hidesRow() {
        RowMatcher matcher = new RowMatcher(List.of(deny(rule("status", RuleOperator.EQ, "archived"))));
        assertThat(matcher.matches("t", row("status", null), alice(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("an OR policy is true when any rule is true, even beside a NULL comparison")
    void or_trueBesideUnknown_matches() {
        RowMatcher matcher = new RowMatcher(List.of(allowOr(
            eq("owner_id", "{{currentUserId}}"), rule("status", RuleOperator.EQ, "public"))));
        assertThat(matcher.matches("t", row("owner_id", null, "status", "public"), alice(), Operation.SELECT)).isTrue();
        assertThat(matcher.matches("t", row("owner_id", null, "status", "draft"), alice(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("an AND policy is false when any rule is false, even beside a NULL comparison")
    void and_falseBesideUnknown_isFalse() {
        RowMatcher matcher = new RowMatcher(List.of(
            allow(rule("id", RuleOperator.IS_NOT_NULL, null)),
            denyAnd(rule("status", RuleOperator.EQ, "archived"), rule("region", RuleOperator.EQ, "west"))));
        assertThat(matcher.matches("t", row("id", 1, "status", null, "region", "east"), alice(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("a policy column missing from the change image excludes the row (key-only DELETE)")
    void absentColumn_allow_excluded() {
        RowMatcher eqOwner = new RowMatcher(List.of(allow(eq("owner_id", "{{currentUserId}}"))));
        RowMatcher isNull = new RowMatcher(List.of(allow(rule("archived_at", RuleOperator.IS_NULL, null))));
        RowMatcher neq = new RowMatcher(List.of(allow(rule("status", RuleOperator.NEQ, "archived"))));
        Map<String, Object> keyOnly = row("id", 7);
        assertThat(eqOwner.matches("t", keyOnly, anon(), Operation.SELECT)).isFalse();
        assertThat(isNull.matches("t", keyOnly, alice(), Operation.SELECT)).isFalse();
        assertThat(neq.matches("t", keyOnly, alice(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("a DENY column missing from the change image hides the row")
    void absentColumn_deny_hidesRow() {
        RowMatcher matcher = new RowMatcher(List.of(deny(rule("status", RuleOperator.EQ, "archived"))));
        assertThat(matcher.matches("t", row("id", 7), alice(), Operation.SELECT)).isFalse();
    }

    @Test
    @DisplayName("a missing column that cannot change the outcome does not hide the row")
    void absentColumn_irrelevant_kept() {
        RowMatcher matcher = new RowMatcher(List.of(allowOr(
            eq("owner_id", "{{currentUserId}}"), rule("status", RuleOperator.EQ, "public"))));
        assertThat(matcher.matches("t", row("owner_id", ALICE), alice(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("a table without RLS keeps a key-only image")
    void noPolicies_keyOnly_kept() {
        assertThat(new RowMatcher(List.of()).matches("t", row("id", 7), anon(), Operation.SELECT)).isTrue();
    }

    @Test
    @DisplayName("WITH CHECK: setting a DENY column to NULL is rejected, as NOT (NULL) fails a check")
    void update_setDenyColumnNull_rejected() {
        RowMatcher matcher = new RowMatcher(List.of(
            allow(rule("id", RuleOperator.IS_NOT_NULL, null)),
            deny(rule("status", RuleOperator.EQ, "archived"))));
        assertThat(matcher.matchesUpdate("t", row("status", null), alice())).isFalse();
        assertThat(matcher.matchesUpdate("t", row("status", "active"), alice())).isTrue();
    }

    @Test
    @DisplayName("WITH CHECK: setting an owner column to NULL is rejected")
    void update_setOwnerNull_rejected() {
        RowMatcher matcher = new RowMatcher(List.of(allow(eq("owner_id", "{{currentUserId}}"))));
        assertThat(matcher.matchesUpdate("t", row("owner_id", null), alice())).isFalse();
        assertThat(matcher.matchesUpdate("t", row("owner_id", ALICE), alice())).isTrue();
    }

    private static Rule eq(String field, String value) {
        return rule(field, RuleOperator.EQ, value);
    }

    private static Rule rule(String field, RuleOperator op, String value) {
        return new Rule(field, FieldType.STRING, op, value);
    }

    private static Policy allow(Rule rule) {
        return policy(PolicyEffect.ALLOW, LogicOperator.AND, List.of(rule));
    }

    private static Policy allowOr(Rule... rules) {
        return policy(PolicyEffect.ALLOW, LogicOperator.OR, List.of(rules));
    }

    private static Policy deny(Rule rule) {
        return policy(PolicyEffect.DENY, LogicOperator.AND, List.of(rule));
    }

    private static Policy denyAnd(Rule... rules) {
        return policy(PolicyEffect.DENY, LogicOperator.AND, List.of(rules));
    }

    private static Policy policy(PolicyEffect effect, LogicOperator logic, List<Rule> rules) {
        return new Policy("p-" + effect, "p", "t", effect, Operation.ALL, logic, 0, true, rules,
            List.of(Assignment.all()));
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) row.put((String) keyValues[i], keyValues[i + 1]);
        return row;
    }

    private static UserContext anon() {
        return ctx(null, Set.of("anon"));
    }

    private static UserContext alice() {
        return ctx(ALICE, Set.of("authenticated"));
    }

    private static UserContext ctx(String userId, Set<String> roles) {
        return new UserContext() {
            @Override public String userId() { return userId; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return roles; }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
