package io.github.excalibase.permissions.compile;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.rls.UserContext;
import io.github.excalibase.rls.jdbc.JdbcEvaluator;
import io.github.excalibase.rls.jdbc.SqlFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U2;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrated policies behave identically: every legacy rule shape the control plane folds into a
 * permission (spec §9) keeps the same rows under the old JDBC evaluator and the new compiler.
 * {@code {{currentUserId}}} becomes {@code X-Excalibase-User-Id}, a custom claim {@code {{team}}}
 * becomes {@code X-Excalibase-Team}, and a DENY becomes {@code _not}.
 */
class LegacyPolicyParityTest {

    private static final Map<String, String> VARIABLES = Map.of(
            "x-excalibase-user-id", U2, "x-excalibase-team", "open");

    static Stream<Arguments> ruleShapes() {
        return Stream.of(
                shape("owner_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}", "_eq", "\"X-Excalibase-User-Id\""),
                shape("owner_id", FieldType.UUID, RuleOperator.NEQ, "{{currentUserId}}", "_neq", "\"X-Excalibase-User-Id\""),
                shape("owner_id", FieldType.UUID, RuleOperator.IN, "{{currentUserId}}", "_in", "\"X-Excalibase-User-Id\""),
                shape("owner_id", FieldType.UUID, RuleOperator.NOT_IN, "{{currentUserId}}", "_nin", "\"X-Excalibase-User-Id\""),
                shape("status", FieldType.STRING, RuleOperator.EQ, "{{team}}", "_eq", "\"X-Excalibase-Team\""),
                shape("amount", FieldType.DECIMAL, RuleOperator.GT, "0", "_gt", "0"),
                shape("amount", FieldType.DECIMAL, RuleOperator.GTE, "10.5", "_gte", "10.5"),
                shape("amount", FieldType.DECIMAL, RuleOperator.LT, "0", "_lt", "0"),
                shape("amount", FieldType.DECIMAL, RuleOperator.LTE, "10.5", "_lte", "10.5"),
                shape("priority", FieldType.INTEGER, RuleOperator.EQ, "2", "_eq", "2"),
                shape("priority", FieldType.INTEGER, RuleOperator.NEQ, "2", "_neq", "2"),
                shape("created_at", FieldType.DATETIME, RuleOperator.GT, "2026-01-01T10:00:00Z",
                        "_gt", "\"2026-01-01T10:00:00Z\""),
                shape("status", FieldType.STRING, RuleOperator.IN, "open,draft", "_in", "[\"open\", \"draft\"]"),
                shape("status", FieldType.STRING, RuleOperator.NOT_IN, "open,draft", "_nin", "[\"open\", \"draft\"]"),
                shape("title", FieldType.STRING, RuleOperator.LIKE, "A%", "_like", "\"A%\""),
                shape("title", FieldType.STRING, RuleOperator.NOT_LIKE, "A%", "_nlike", "\"A%\""),
                shape("status", FieldType.STRING, RuleOperator.IS_NULL, null, "_is_null", "true"),
                shape("status", FieldType.STRING, RuleOperator.IS_NOT_NULL, null, "_is_null", "false"));
    }

    private static Arguments shape(String column, FieldType type, RuleOperator operator, String value,
                                   String hasuraOperator, String hasuraValue) {
        String expression = "{\"" + column + "\": {\"" + hasuraOperator + "\": " + hasuraValue + "}}";
        return Arguments.of(new Rule(column, type, operator, value), expression);
    }

    @ParameterizedTest(name = "ALLOW {0}")
    @MethodSource("ruleShapes")
    void allowPolicy_keepsTheSameRowsAsItsPermission(Rule rule, String expression) {
        List<Policy> legacy = List.of(policy(PolicyEffect.ALLOW, rule));

        assertThat(DifferentialFixture.engineRows(NOTES, parse(expression), VARIABLES)).isEqualTo(legacyRows(legacy));
    }

    @ParameterizedTest(name = "DENY {0}")
    @MethodSource("ruleShapes")
    void denyPolicy_keepsTheSameRowsAsItsNegatedPermission(Rule rule, String expression) {
        List<Policy> legacy = List.of(
                policy(PolicyEffect.ALLOW, new Rule("id", FieldType.INTEGER, RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, rule));

        assertThat(DifferentialFixture.engineRows(NOTES, parse("{\"_not\": " + expression + "}"), VARIABLES))
                .isEqualTo(legacyRows(legacy));
    }

    private static TreeSet<String> legacyRows(List<Policy> policies) {
        SqlFilter filter = new JdbcEvaluator(policies).compile("notes", caller(), Operation.SELECT);
        String where = filter.isUnrestricted() ? "TRUE" : filter.sql();
        return new TreeSet<>(new NamedParameterJdbcTemplate(DifferentialFixture.dataSource()).queryForList(
                "SELECT id::text FROM " + NOTES + " WHERE " + where, new MapSqlParameterSource(filter.params()),
                String.class));
    }

    private static Policy policy(PolicyEffect effect, Rule rule) {
        return new Policy("p-" + effect, "p", "notes", effect, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(rule), List.of(Assignment.all()));
    }

    private static UserContext caller() {
        return new UserContext() {
            @Override public String userId() { return U2; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) { return "team".equals(name) ? "open" : null; }
        };
    }
}
