package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.spi.QueryEngine;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.excalibase.mongodb.Fixtures.policy;
import static io.github.excalibase.mongodb.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents carry no schema, so some will not match their declaration. A document that does not hold a
 * scalar of the declared type must never satisfy an ALLOW or slip past a DENY: it is unknown, and unknown
 * is hidden, as a NULL is in SQL.
 */
abstract class EngineTypeDriftTest extends AbstractEngineTest {

    private static List<Document> drifted() {
        return List.of(
                new Document("_id", 1).append("status", "ok"),
                new Document("_id", 2).append("status", 123),
                new Document("_id", 3).append("status", List.of("banned", "ok")),
                new Document("_id", 4).append("status", "banned"),
                new Document("_id", 5).append("status", null),
                new Document("_id", 6));
    }

    private static final Policy EVERYTHING = policy(PolicyEffect.ALLOW, rule("id", RuleOperator.IS_NOT_NULL, null));

    private List<String> visible(List<Policy> policies) {
        QueryEngine engine = engineOver(drifted(), policies, List.of(), List.of());
        Map<String, Object> response = engine.execute("{ orders(orderBy: [{field: id}]) { id } }", Map.of(), caller("u"));
        assertThat(error(response)).isNull();
        return rows(response, "orders").stream().map(row -> String.valueOf(row.get("id"))).toList();
    }

    @Override
    String idKind() {
        return "int";
    }

    @Test
    void aDenyDoesNotLetDriftedDocumentsThrough() {
        // only the document that is a plain string different from "banned" is definitely not denied
        assertThat(visible(List.of(EVERYTHING, policy(PolicyEffect.DENY, rule("status", RuleOperator.EQ, "banned")))))
                .containsExactly("1");
    }

    @Test
    void anAllowIsNotSatisfiedByAnArrayHoldingTheValue() {
        assertThat(visible(List.of(policy(PolicyEffect.ALLOW, rule("status", RuleOperator.EQ, "ok")))))
                .containsExactly("1");
    }

    @Test
    void aNegatedAllowIsNotSatisfiedByAValueOfAnotherType() {
        assertThat(visible(List.of(policy(PolicyEffect.ALLOW, rule("status", RuleOperator.NEQ, "draft")))))
                .containsExactly("1", "4");
    }
}
