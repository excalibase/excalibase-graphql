package io.github.excalibase.mongodb;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.ColumnSet;
import io.github.excalibase.permissions.ComparisonOperator;
import io.github.excalibase.permissions.DeletePermission;
import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.SelectPermission;
import io.github.excalibase.permissions.SessionVariable;
import io.github.excalibase.permissions.TablePermissions;
import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionTranslatorTest {

    private static final String STATUS = "status";
    private static final String TOTAL = "total";
    private static final String PAID = "paid";
    private static final String NOPE = "nope";
    private static final String ID = "id";
    private static final String DATABASE = "app";
    private static final String ORDERS = "app.orders";
    private static final String USER = "user";
    private static final String OWNER = "owner";
    private static final String USER_ID_VARIABLE = "x-excalibase-user-id";

    private static PermissionSet document(TablePermissions... tables) {
        return new PermissionSet("p1", 1, List.of(tables), List.of(), List.of());
    }

    private static TablePermissions select(String table, String role, BoolExp filter, ColumnSet columns) {
        return new TablePermissions(table, role, Optional.of(new SelectPermission(filter, columns, null, false)),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ColumnCompare compare(String column, ComparisonOperator operator, Object literal) {
        return new ColumnCompare(column, operator, new Literal(literal));
    }

    private static PermissionTranslator.Translation translate(PermissionSet set) {
        return PermissionTranslator.translate(set, DATABASE, Fixtures.catalog());
    }

    private static Policy only(PermissionTranslator.Translation translation) {
        assertThat(translation.policies()).hasSize(1);
        return translation.policies().getFirst();
    }

    @Test
    void aColumnEqualityBecomesAnAllowPolicyForThatRoleAndCollection() {
        var translation = translate(document(select(ORDERS, USER, compare(OWNER, ComparisonOperator.EQ, "u1"), ColumnSet.ALL)));

        Policy policy = only(translation);
        assertThat(policy.resource()).isEqualTo("orders");
        assertThat(policy.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(policy.operations()).containsExactly(Operation.SELECT);
        assertThat(policy.assignments()).extracting(a -> a.targetId()).containsExactly(USER);
        assertThat(policy.rules()).containsExactly(new Rule(OWNER, FieldType.STRING, RuleOperator.EQ, "u1"));
        assertThat(translation.columnPolicies()).isEmpty();
    }

    @Test
    void aSessionVariableBecomesAClaimTheCallerResolves() {
        var filter = new ColumnCompare(OWNER, ComparisonOperator.EQ, new SessionVariable("X-Excalibase-User-Id"));

        Policy policy = only(translate(document(select(ORDERS, USER, filter, ColumnSet.ALL))));

        assertThat(policy.rules().getFirst().value()).isEqualTo("{{" + USER_ID_VARIABLE + "}}");
    }

    @Test
    void fieldTypesFollowTheDeclaredKinds() {
        var filter = new BoolExp.And(List.of(compare(TOTAL, ComparisonOperator.GTE, 5L),
                compare(PAID, ComparisonOperator.EQ, true), compare(STATUS, ComparisonOperator.LIKE, "op%")));

        Policy policy = only(translate(document(select(ORDERS, USER, filter, ColumnSet.ALL))));

        assertThat(policy.ruleLogic()).isEqualTo(LogicOperator.AND);
        assertThat(policy.rules()).containsExactly(
                new Rule(TOTAL, FieldType.INTEGER, RuleOperator.GTE, "5"),
                new Rule(PAID, FieldType.BOOLEAN, RuleOperator.EQ, "true"),
                new Rule(STATUS, FieldType.STRING, RuleOperator.LIKE, "op%"));
    }

    @Test
    void everyComparisonOperatorHasARuleOperator() {
        var expected = Map.ofEntries(
                Map.entry(ComparisonOperator.EQ, RuleOperator.EQ), Map.entry(ComparisonOperator.NEQ, RuleOperator.NEQ),
                Map.entry(ComparisonOperator.GT, RuleOperator.GT), Map.entry(ComparisonOperator.GTE, RuleOperator.GTE),
                Map.entry(ComparisonOperator.LT, RuleOperator.LT), Map.entry(ComparisonOperator.LTE, RuleOperator.LTE));
        expected.forEach((operator, rule) -> {
            Policy policy = only(translate(document(select(ORDERS, USER, compare(TOTAL, operator, 7L), ColumnSet.ALL))));
            assertThat(policy.rules().getFirst().operator()).isEqualTo(rule);
        });
    }

    @Test
    void inAndNotInTakeAListOfLiterals() {
        Policy in = only(translate(document(select(ORDERS, USER,
                compare(STATUS, ComparisonOperator.IN, List.of("open", PAID)), ColumnSet.ALL))));
        Policy notIn = only(translate(document(select(ORDERS, USER,
                compare(STATUS, ComparisonOperator.NIN, List.of("open")), ColumnSet.ALL))));

        assertThat(in.rules().getFirst()).isEqualTo(new Rule(STATUS, FieldType.STRING, RuleOperator.IN, "open,paid"));
        assertThat(notIn.rules().getFirst().operator()).isEqualTo(RuleOperator.NOT_IN);
    }

    @Test
    void isNullTrueAndFalseAreTheTwoNullTests() {
        Policy isNull = only(translate(document(select(ORDERS, USER, compare(STATUS, ComparisonOperator.IS_NULL, true), ColumnSet.ALL))));
        Policy isNotNull = only(translate(document(select(ORDERS, USER, compare(STATUS, ComparisonOperator.IS_NULL, false), ColumnSet.ALL))));

        assertThat(isNull.rules().getFirst().operator()).isEqualTo(RuleOperator.IS_NULL);
        assertThat(isNotNull.rules().getFirst().operator()).isEqualTo(RuleOperator.IS_NOT_NULL);
    }

    @Test
    void anOrOfComparisonsIsOneOrPolicy() {
        var filter = new BoolExp.Or(List.of(compare(OWNER, ComparisonOperator.EQ, "u1"), compare(PAID, ComparisonOperator.EQ, true)));

        Policy policy = only(translate(document(select(ORDERS, USER, filter, ColumnSet.ALL))));

        assertThat(policy.ruleLogic()).isEqualTo(LogicOperator.OR);
        assertThat(policy.rules()).hasSize(2);
    }

    @Test
    void anEmptyFilterAllowsEveryDocumentWithAnId() {
        Policy policy = only(translate(document(select(ORDERS, USER, BoolExp.TRUE, ColumnSet.ALL))));

        assertThat(policy.rules()).containsExactly(new Rule(ID, FieldType.STRING, RuleOperator.IS_NOT_NULL, null));
    }

    @Test
    void aColumnListHidesTheDeclaredFieldsItLeavesOut() {
        var columns = ColumnSet.of(List.of(ID, OWNER, STATUS));

        var translation = translate(document(select(ORDERS, USER, BoolExp.TRUE, columns)));

        assertThat(translation.columnPolicies()).hasSize(1);
        var hide = translation.columnPolicies().getFirst();
        assertThat(hide.mode()).isEqualTo(MaskMode.HIDE);
        assertThat(hide.columns()).containsExactlyInAnyOrder(TOTAL, PAID, "placedAt", "secret");
        assertThat(hide.assignments()).extracting(a -> a.targetId()).containsExactly(USER);
    }

    @Test
    void aCollectionNobodyMayReadGetsAPolicyThatMatchesNothing() {
        var translation = translate(document());

        Policy policy = only(translation);
        assertThat(policy.effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(policy.rules()).extracting(Rule::operator)
                .containsExactly(RuleOperator.IS_NULL, RuleOperator.IS_NOT_NULL);
        assertThat(policy.ruleLogic()).isEqualTo(LogicOperator.AND);
    }

    @Test
    void writePermissionsGrantNothingBecauseThereIsNoWriteSurface() {
        var write = new TablePermissions(ORDERS, USER, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new DeletePermission(BoolExp.TRUE)));

        var translation = translate(document(write));

        assertThat(only(translation).rules()).extracting(Rule::operator).containsExactly(RuleOperator.IS_NULL, RuleOperator.IS_NOT_NULL);
    }

    // ---- refusals: what cannot be expressed exactly is refused at load, never weakened ----

    private static void refused(BoolExp filter, String reason) {
        assertThatThrownBy(() -> translate(document(select(ORDERS, USER, filter, ColumnSet.ALL))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(reason);
    }

    @Test
    void notIsRefused() {
        refused(new BoolExp.Not(compare(OWNER, ComparisonOperator.EQ, "u1")), "_not");
    }

    @Test
    void nestedGroupsAreRefused() {
        refused(new BoolExp.And(List.of(new BoolExp.Or(List.of(compare(OWNER, ComparisonOperator.EQ, "a"))),
                compare(PAID, ComparisonOperator.EQ, true))), "nested");
    }

    @Test
    void relationshipsAndExistsAreRefused() {
        refused(new BoolExp.Relationship("customer", BoolExp.TRUE), "relationship");
        refused(new BoolExp.Exists("app.other", BoolExp.TRUE), "_exists");
    }

    @Test
    void aSessionVariableCheckIsRefused() {
        refused(new BoolExp.SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.EQ, new Literal("aal2")),
                "session variable");
    }

    @Test
    void aSessionVariableListIsRefused() {
        refused(new ColumnCompare(OWNER, ComparisonOperator.IN, new SessionVariable(USER_ID_VARIABLE)), "_in");
    }

    @Test
    void aLiteralThatLooksLikeAClaimIsRefused() {
        refused(compare(OWNER, ComparisonOperator.EQ, "{{currentUserId}}"), "claim");
    }

    @Test
    void aListValueThatTheRuleModelCouldSplitIsRefused() {
        refused(compare(STATUS, ComparisonOperator.IN, List.of("a,b")), "comma");
        refused(compare(STATUS, ComparisonOperator.IN, List.of(" a")), "space");
    }

    @Test
    void aLiteralOfTheWrongTypeForTheFieldIsRefused() {
        refused(compare(TOTAL, ComparisonOperator.EQ, "5"), TOTAL);
        refused(compare(OWNER, ComparisonOperator.EQ, 5L), OWNER);
        refused(compare(PAID, ComparisonOperator.EQ, "true"), PAID);
    }

    @Test
    void aNullLiteralOnAComparisonIsRefused() {
        refused(compare(OWNER, ComparisonOperator.EQ, null), "null");
    }

    @Test
    void aFieldTheCollectionDoesNotDeclareIsRefused() {
        refused(compare(NOPE, ComparisonOperator.EQ, "x"), NOPE);
    }

    @Test
    void aBigDecimalForAnIntegerFieldWithAFractionIsRefused() {
        refused(compare(TOTAL, ComparisonOperator.EQ, new BigDecimal("1.5")), TOTAL);
    }

    @Test
    void aTableOfAnotherDatabaseOrAnUndeclaredCollectionIsRefused() {
        assertThatThrownBy(() -> translate(document(select("other.orders", USER, BoolExp.TRUE, ColumnSet.ALL))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("other.orders");
        assertThatThrownBy(() -> translate(document(select("app.ghosts", USER, BoolExp.TRUE, ColumnSet.ALL))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.ghosts");
    }

    @Test
    void aColumnListWithoutTheIdOrWithAnUnknownColumnIsRefused() {
        assertThatThrownBy(() -> translate(document(select(ORDERS, USER, BoolExp.TRUE, ColumnSet.of(List.of(OWNER))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(ID);
        assertThatThrownBy(() -> translate(document(select(ORDERS, USER, BoolExp.TRUE, ColumnSet.of(List.of(ID, NOPE))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(NOPE);
    }

    @Test
    void aRoleRowLimitIsRefusedBecauseTheEngineCannotHonourIt() {
        var limited = new TablePermissions(ORDERS, USER, Optional.of(new SelectPermission(BoolExp.TRUE, ColumnSet.ALL, 5, false)),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> translate(document(limited)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("limit");
    }
}
