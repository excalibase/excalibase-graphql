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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A LIKE pattern that is not text is refused when the SQL is emitted, as the realtime matcher keeps no row for it. */
class LikeNonTextPatternTest {

    @ParameterizedTest
    @EnumSource(value = RuleOperator.class, names = {"LIKE", "NOT_LIKE"})
    void numericAndCollectionClaims_areRefused(RuleOperator op) {
        for (Object value : List.of(5, 5.5, true, Set.of("a"), List.of("a"))) {
            JdbcEvaluator evaluator = new JdbcEvaluator(List.of(policy(op)));
            UserContext context = claim(value);
            assertThatThrownBy(() -> evaluator.compile("t", context, Operation.SELECT))
                .as("%s with %s", op, value).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = RuleOperator.class, names = {"LIKE", "NOT_LIKE"})
    void aTextClaim_isStillEmitted(RuleOperator op) {
        SqlFilter filter = new JdbcEvaluator(List.of(policy(op))).compile("t", claim("a%"), Operation.SELECT);
        assertThat(filter.sql()).contains("LIKE");
        assertThat(filter.params().values()).contains("a%");
    }

    private static Policy policy(RuleOperator op) {
        return new Policy("p", "p", "t", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
            List.of(new Rule("c", FieldType.STRING, op, "{{level}}")), List.of(Assignment.all()));
    }

    private static UserContext claim(Object value) {
        return new UserContext() {
            @Override public String userId() { return "u"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) { return value; }
        };
    }
}
