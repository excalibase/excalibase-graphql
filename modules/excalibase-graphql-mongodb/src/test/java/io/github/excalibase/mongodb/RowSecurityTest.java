package io.github.excalibase.mongodb;

import io.github.excalibase.rls.FieldType;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RelationPredicate;
import io.github.excalibase.rls.Rule;
import io.github.excalibase.rls.RuleOperator;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.UserContext;
import org.bson.types.ObjectId;

import static io.github.excalibase.mongodb.Fixtures.caller;
import static io.github.excalibase.mongodb.Fixtures.policy;
import static io.github.excalibase.mongodb.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowSecurityTest {

    private static RowSecurity security(Policy... policies) {
        return new RowSecurity(Fixtures.catalog(), List.of(policies));
    }

    @Test
    void withoutAnyAllowPolicyTheCollectionIsUnrestricted() {
        assertThat(security().filter("orders", caller("u1"), Operation.SELECT).kind()).isEqualTo(Cond.Kind.ALWAYS);
    }

    @Test
    void anAllowPolicyBecomesAMandatoryFilterBoundToTheCaller() {
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("owner", RuleOperator.EQ, "{{currentUserId}}")));

        assertThat(security.filter("orders", caller("u1"), Operation.SELECT).doc())
                .isEqualTo(new Document("owner", Fixtures.scalar("$eq", "u1")));
        assertThat(security.filter("orders", caller("u2"), Operation.SELECT).doc())
                .isEqualTo(new Document("owner", Fixtures.scalar("$eq", "u2")));
    }

    @Test
    void anAnonymousCallerMatchesNothing() {
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("owner", RuleOperator.EQ, "{{currentUserId}}")));

        assertThat(security.filter("orders", caller(null), Operation.SELECT).kind()).isEqualTo(Cond.Kind.NEVER);
    }

    @Test
    void anAllowThatDoesNotApplyToTheCallerMatchesNothing() {
        Policy admins = new Policy("p", "p", "orders", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(rule("status", RuleOperator.EQ, "open")), List.of(Assignment.role("admin")));

        assertThat(security(admins).filter("orders", caller("u1"), Operation.SELECT).kind())
                .isEqualTo(Cond.Kind.NEVER);
    }

    @Test
    void policiesOfAnotherOperationDoNotOpenARead() {
        Policy insertOnly = policy("orders", PolicyEffect.ALLOW, Set.of(Operation.INSERT), LogicOperator.AND,
                rule("status", RuleOperator.EQ, "open"));

        assertThat(security(insertOnly).filter("orders", caller("u1"), Operation.SELECT).kind())
                .isEqualTo(Cond.Kind.ALWAYS);
        assertThat(security(insertOnly, policy(PolicyEffect.ALLOW, rule("status", RuleOperator.EQ, "x")))
                .filter("orders", caller("u1"), Operation.UPDATE).doc().toJson()).contains("\"x\"").doesNotContain("open");
    }

    @Test
    void denySubtractsOnlyRowsThatAreDefinitelyNotDenied() {
        RowSecurity security = security(
                policy(PolicyEffect.ALLOW, rule("status", RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, rule("secret", RuleOperator.EQ, "x")));

        String json = security.filter("orders", caller("u1"), Operation.SELECT).doc().toJson();
        // a NULL secret is "unknown", and unknown is hidden, exactly as NOT (secret = 'x') is in SQL
        assertThat(json).contains("secret").contains("$ne");
    }

    @Test
    void updateAndDeleteRequireTheRowToBeReadable() {
        RowSecurity security = security(
                policy("orders", PolicyEffect.ALLOW, Set.of(Operation.SELECT), LogicOperator.AND,
                        rule("owner", RuleOperator.EQ, "{{currentUserId}}")),
                policy("orders", PolicyEffect.ALLOW, Set.of(Operation.UPDATE, Operation.DELETE), LogicOperator.AND,
                        rule("status", RuleOperator.EQ, "open")));

        for (Operation write : List.of(Operation.UPDATE, Operation.DELETE)) {
            String json = security.filter("orders", caller("u1"), write).doc().toJson();
            assertThat(json).contains("\"owner\"").contains("\"status\"");
        }
    }

    @Test
    void aDeleteWithoutItsOwnPolicyStillNeedsTheRowToBeReadable() {
        RowSecurity security = security(
                policy("orders", PolicyEffect.ALLOW, Set.of(Operation.SELECT), LogicOperator.AND,
                        rule("status", RuleOperator.EQ, "open")),
                policy("orders", PolicyEffect.ALLOW, Set.of(Operation.UPDATE), LogicOperator.AND,
                        rule("owner", RuleOperator.EQ, "{{currentUserId}}")));

        assertThat(security.filter("orders", caller("u1"), Operation.DELETE).doc())
                .isEqualTo(new Document("status", Fixtures.scalar("$eq", "open")));
    }

    @Test
    void aValueThatLooksLikeAnOperatorIsALiteral() {
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("status", RuleOperator.EQ, "$where")));

        assertThat(security.filter("orders", caller("u1"), Operation.SELECT).doc())
                .isEqualTo(new Document("status", Fixtures.scalar("$eq", "$where")));
    }

    @Test
    void aSessionClaimIsALiteralToo() {
        var hostile = new UserContext() {
            @Override public String userId() { return "{\"$ne\": null}"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("owner", RuleOperator.EQ, "{{currentUserId}}")));

        assertThat(security.filter("orders", hostile, Operation.SELECT).doc())
                .isEqualTo(new Document("owner", Fixtures.scalar("$eq", "{\"$ne\": null}")));
    }

    @Test
    void anArrayOrAValueOfTheWrongTypeIsNeverDefinitelyNotDenied() {
        RowSecurity security = security(
                policy(PolicyEffect.ALLOW, rule("id", RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, rule("status", RuleOperator.EQ, "banned")));

        Document notDenied = security.filter("orders", caller("u1"), Operation.SELECT).doc();

        // the DENY passes a document only if status is a string, not an array, and differs
        assertThat(notDenied.toJson()).contains("\"$type\": \"string\"").contains("\"$ne\": \"banned\"")
                .contains("\"$not\": {\"$type\": \"array\"}");
    }

    @Test
    void aPolicyThatTargetsNoDeclaredCollectionIsRefusedBecauseItWouldNeverApply() {
        Policy typo = policy("ordres", PolicyEffect.DENY, Set.of(Operation.SELECT), LogicOperator.AND,
                rule("status", RuleOperator.EQ, "x"));

        assertThatThrownBy(() -> security(typo))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ordres");
    }

    @Test
    void aClaimOfTheWrongTypeNamesNoFieldToTheCaller() {
        Rule rule = new Rule("total", FieldType.INTEGER, RuleOperator.GT, "{{level}}");
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule));
        var wrong = new UserContext() {
            @Override public String userId() { return "u"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) { return 5L; }
        };

        assertThatThrownBy(() -> security.filter("orders", wrong, Operation.SELECT))
                .isNotInstanceOf(RequestException.class).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relationshipPredicatesAreRefusedAtStartup() {
        Policy withRelation = new Policy("p", "p", "orders", PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0,
                true, List.of(), List.of(new RelationPredicate("members", "org", "org", LogicOperator.AND, List.of())),
                List.of(Assignment.all()));

        assertThatThrownBy(() -> security(withRelation))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("relationship");
    }

    @Test
    void ruleOnAFieldThatIsNotDeclaredIsRefusedAtStartup() {
        Policy misspelt = policy(PolicyEffect.ALLOW, rule("ownr", RuleOperator.EQ, "x"));
        assertThatThrownBy(() -> security(misspelt))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownr");
    }

    @Test
    void ruleWhoseTypeDisagreesWithTheDeclaredFieldIsRefusedAtStartup() {
        Rule wrong = new Rule("total", FieldType.STRING, RuleOperator.EQ, "5");
        Policy mistyped = policy(PolicyEffect.ALLOW, wrong);
        assertThatThrownBy(() -> security(mistyped))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("total");
    }

    @Test
    void anObjectIdFieldIsComparedAsAnObjectId() {
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("id", RuleOperator.EQ, "65f0c0ffee0000000000abcd")));

        Document doc = security.filter("orders", caller("u1"), Operation.SELECT).doc();
        assertThat(doc.get("_id", Document.class).get("$eq")).isInstanceOf(ObjectId.class);
    }

    @Test
    void anObjectIdRuleThatIsNotAnObjectIdIsRefusedAtStartup() {
        Policy badId = policy(PolicyEffect.ALLOW, rule("id", RuleOperator.EQ, "nope"));
        assertThatThrownBy(() -> security(badId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void likeBecomesAnAnchoredEscapedPattern() {
        RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("status", RuleOperator.LIKE, "a.b%")));

        Document pattern = security.filter("orders", caller("u1"), Operation.SELECT).doc().get("status", Document.class);
        assertThat(pattern).containsEntry("$regex", "\\A\\Qa.b\\E").containsEntry("$options", "s");
    }

    @Test
    void likeRunsOfPercentCollapseAndMiddleSegmentsAreAtomic() {
        assertThat(Predicates.likeToRegex("a%%%b%c%d"))
                .isEqualTo("\\A\\Qa\\E(?>.*?\\Qb\\E)(?>.*?\\Qc\\E).*\\Qd\\E\\z");
        assertThat(Predicates.likeToRegex("%")).isEqualTo("\\A");
        assertThat(Predicates.likeToRegex("a%")).isEqualTo("\\A\\Qa\\E");
        assertThat(Predicates.likeToRegex("a_c")).isEqualTo("\\A\\Qa\\E.\\Qc\\E\\z");
    }

    @Test
    void likeWithATrailingEscapeMatchesNothingAndSoDoesItsNegation() {
        Tri like = Predicates.like("status", "act\\", true);

        assertThat(like.t().kind()).isEqualTo(Cond.Kind.NEVER);
        assertThat(like.not().t().kind()).isEqualTo(Cond.Kind.NEVER);
    }

    @Test
    void likeWithTooManyWildcardSegmentsOrTooLongMatchesNothing() {
        String hostile = "%a".repeat(Predicates.MAX_LIKE_SEGMENTS + 1);

        assertThat(Predicates.like("status", hostile, true).t().kind()).isEqualTo(Cond.Kind.NEVER);
        assertThat(Predicates.like("status", hostile, true).f().kind()).isEqualTo(Cond.Kind.NEVER);
        assertThat(Predicates.like("status", "a".repeat(Predicates.MAX_LIKE_LENGTH + 1), true).t().kind())
                .isEqualTo(Cond.Kind.NEVER);
    }

    @Test
    void aLikeLiteralOverTheCapsOrEndingInAnEscapeRefusesStartup() {
        Policy tooMany = policy(PolicyEffect.ALLOW, rule("status", RuleOperator.LIKE, "%a".repeat(Predicates.MAX_LIKE_SEGMENTS + 1)));
        Policy tooLong = policy(PolicyEffect.ALLOW, rule("status", RuleOperator.NOT_LIKE, "a".repeat(Predicates.MAX_LIKE_LENGTH + 1)));
        Policy escaped = policy(PolicyEffect.ALLOW, rule("status", RuleOperator.LIKE, "act\\"));
        // only a value that both starts with {{ and ends with }} is a claim; anything else is a literal
        Policy notAClaim = policy(PolicyEffect.ALLOW, rule("status", RuleOperator.LIKE, "{{" + "%a".repeat(Predicates.MAX_LIKE_SEGMENTS + 1)));

        for (Policy bad : List.of(tooMany, tooLong, escaped, notAClaim)) {
            assertThatThrownBy(() -> security(bad)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LIKE").hasMessageContaining("status");
        }
    }

    @Test
    void aLikeLiteralWithinTheCapsIsAccepted() {
        assertThat(security(policy(PolicyEffect.ALLOW, rule("status", RuleOperator.LIKE, "%a".repeat(Predicates.MAX_LIKE_SEGMENTS))))
                .filter("orders", caller("u1"), Operation.SELECT).kind()).isEqualTo(Cond.Kind.EXPR);
    }

    @Test
    void aClaimThatIsNotATextIsUnknownNotAnError() {
        UserContext numeric = new UserContext() {
            @Override public String userId() { return "u1"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
            @Override public Object resolveVariable(String name) { return 42; }
        };
        for (RuleOperator like : List.of(RuleOperator.LIKE, RuleOperator.NOT_LIKE)) {
            RowSecurity security = security(policy(PolicyEffect.ALLOW, rule("status", like, "{{tier}}")));

            assertThat(security.filter("orders", numeric, Operation.SELECT).kind()).as(like.name()).isEqualTo(Cond.Kind.NEVER);
        }
    }

    @Test
    void theConstantConditionsCannotBeChangedThroughTheirDocument() {
        Cond.ALWAYS.doc().put("owner", "x");
        Cond.NEVER.doc().put("owner", "x");

        assertThat(Cond.ALWAYS.doc()).isEmpty();
        assertThat(Cond.NEVER.doc()).containsOnlyKeys("_id");
    }
}
