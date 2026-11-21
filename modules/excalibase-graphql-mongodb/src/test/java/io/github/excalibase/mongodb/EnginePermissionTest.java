package io.github.excalibase.mongodb;

import io.github.excalibase.rls.Assignment;
import io.github.excalibase.rls.ColumnPolicy;
import io.github.excalibase.rls.MaskMode;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.Policy;
import io.github.excalibase.rls.PolicyEffect;
import io.github.excalibase.rls.RuleOperator;
import io.github.excalibase.spi.QueryEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.excalibase.rls.LogicOperator;
import io.github.excalibase.rls.UserContext;

import static io.github.excalibase.mongodb.Fixtures.policy;
import static io.github.excalibase.mongodb.Fixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission guarantees, against a real database: a document the caller may not see is never returned,
 * by list, by id or by any filter, and nothing a request carries can widen the caller's access.
 */
abstract class EnginePermissionTest extends AbstractEngineTest {

    private static final Policy OWN_ROWS = policy(PolicyEffect.ALLOW, rule("owner", RuleOperator.EQ, "{{currentUserId}}"));

    private QueryEngine owned() {
        return engine(List.of(OWN_ROWS), List.of(), List.of());
    }

    private static Map<String, Object> as(QueryEngine engine, String user, String query) {
        return engine.execute(query, Map.of(), caller(user));
    }

    @Test
    void aCallerSeesOnlyTheirOwnDocuments() {
        QueryEngine engine = owned();

        assertThat(rows(as(engine, "u1", "{ orders { owner } }"), "orders")).extracting(r -> r.get("owner"))
                .containsOnly("u1").hasSize(2);
        assertThat(rows(as(engine, "u2", "{ orders { owner } }"), "orders")).extracting(r -> r.get("owner"))
                .containsOnly("u2").hasSize(2);
    }

    @Test
    void anAnonymousCallerSeesNothing() {
        assertThat(rows(as(owned(), null, "{ orders { id } }"), "orders")).isEmpty();
    }

    @Test
    void aHiddenDocumentIsNotReachableById() {
        QueryEngine engine = owned();

        Map<String, Object> response = as(engine, "u1", "{ ordersById(id: \"" + ID_3 + "\") { id owner } }");

        assertThat(error(response)).isNull();
        assertThat(one(response, "ordersById")).isNull();
    }

    @Test
    void aFilterCanNarrowButNeverWiden() {
        QueryEngine engine = owned();

        for (String where : List.of(
                "{owner: {eq: \"u2\"}}", "{owner: {neq: \"u1\"}}", "{owner: {in: [\"u2\"]}}", "{owner: {isNull: true}}",
                "{id: {in: [\"" + ID_3 + "\", \"" + ID_4 + "\"]}}", "{status: {eq: \"$where\"}}", "{total: {gt: 30}}")) {
            Map<String, Object> response = as(engine, "u1", "{ orders(where: " + where + ") { id owner } }");
            assertThat(error(response)).as(where).isNull();
            assertThat(rows(response, "orders")).as(where).allSatisfy(row -> assertThat(row).containsEntry("owner", "u1"));
        }
        assertThat(rows(as(engine, "u1", "{ orders(where: {owner: {in: [\"u2\", \"u1\"]}}) { id } }"), "orders")).hasSize(2);
    }

    @Test
    void aClaimThatLooksLikeAnOperatorIsJustAnId() {
        QueryEngine engine = owned();

        assertThat(rows(as(engine, "{\"$ne\": null}", "{ orders { id } }"), "orders")).isEmpty();
        assertThat(rows(as(engine, "$where", "{ orders { id } }"), "orders")).isEmpty();
    }

    @Test
    void denySubtractsEvenWhenAnAllowMatches() {
        QueryEngine engine = engine(List.of(
                policy(PolicyEffect.ALLOW, rule("id", RuleOperator.IS_NOT_NULL, null)),
                policy(PolicyEffect.DENY, rule("status", RuleOperator.EQ, "closed"))), List.of(), List.of());

        // order 2 is closed (denied); order 5 has no status, so the deny is unknown and hides it too
        assertThat(rows(as(engine, "u1", "{ orders { id } }"), "orders")).extracting(r -> r.get("id"))
                .containsExactlyInAnyOrder(ID_1, ID_3, ID_4);
    }

    @Test
    void aRoleScopedPolicyOnlyOpensTheDocumentsForThatRole() {
        Policy adminsSeeAll = new Policy("a", "admins", "orders", PolicyEffect.ALLOW, Operation.ALL,
                LogicOperator.AND, 0, true, List.of(rule("id", RuleOperator.IS_NOT_NULL, null)),
                List.of(Assignment.role("admin")));
        QueryEngine engine = engine(List.of(OWN_ROWS, adminsSeeAll), List.of(), List.of());
        var admin = new UserContext() {
            @Override public String userId() { return "u1"; }
            @Override public String tenantId() { return null; }
            @Override public Set<String> roles() { return Set.of("admin"); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };

        assertThat(rows(engine.execute("{ orders { id } }", Map.of(), admin), "orders")).hasSize(5);
        assertThat(rows(as(engine, "u1", "{ orders { id } }"), "orders")).hasSize(2);
    }

    // ---- column rules ----

    private QueryEngine masked() {
        ColumnPolicy hide = new ColumnPolicy("c1", "hide", "orders", Set.of("secret"), Operation.ALL, MaskMode.HIDE, null,
                null, 0, true, List.of(Assignment.all()));
        ColumnPolicy blank = new ColumnPolicy("c2", "null", "orders", Set.of("total"), Operation.ALL, MaskMode.NULL, null,
                null, 0, true, List.of(Assignment.all()));
        return engine(List.of(OWN_ROWS), List.of(hide, blank), List.of());
    }

    @Test
    void aHiddenColumnIsNotInTheSchemaAndSoCannotBeAskedFor() {
        QueryEngine engine = masked();

        assertThat(error(as(engine, "u1", "{ orders { secret } }"))).isNotNull();
        assertThat(error(as(engine, "u1", "{ orders(where: {secret: {eq: \"s1\"}}) { id } }"))).isNotNull();
        assertThat(error(as(engine, "u1", "{ orders(orderBy: [{field: secret}]) { id } }"))).isNotNull();
    }

    @Test
    void aNullMaskedColumnIsNullAndCannotBeUsedToProbeIt() {
        QueryEngine engine = masked();

        assertThat(rows(as(engine, "u1", "{ orders { total owner } }"), "orders"))
                .allSatisfy(row -> assertThat(row).containsEntry("total", null));
        assertThat(error(as(engine, "u1", "{ orders(where: {total: {gt: 15}}) { id } }"))).isNotNull();
        assertThat(error(as(engine, "u1", "{ orders(orderBy: [{field: total}]) { id } }"))).isNotNull();
    }

    @Test
    void introspectionShowsOnlyWhatTheCallerMaySee() {
        Map<String, Object> response = as(masked(), "u1", "{ __type(name: \"Orders\") { fields { name } } }");

        assertThat(response.toString()).contains("owner").doesNotContain("secret");
    }

    @Test
    void aHostileLikePatternFromAClaimIsRefusedInBoundedTimeNotBacktracked() {
        Policy byPattern = policy(PolicyEffect.ALLOW, rule("owner", RuleOperator.LIKE, "{{currentUserId}}"));
        QueryEngine engine = engine(List.of(byPattern), List.of(), List.of());
        String hostile = "%a".repeat(40) + "b";

        Map<String, Object> response = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(10), () -> as(engine, hostile, "{ orders { id } }"));

        assertThat(rows(response, "orders")).isEmpty();
    }
}
