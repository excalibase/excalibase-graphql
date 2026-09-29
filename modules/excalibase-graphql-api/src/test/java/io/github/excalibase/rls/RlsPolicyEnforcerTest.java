package io.github.excalibase.rls;

import io.github.excalibase.rls.jdbc.SqlFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link RlsPolicyEnforcer} — the seam that turns a request
 * (projectId + table + JWT + operation) into a parameterized RLS WHERE
 * fragment by consulting the {@link PolicyProvider} and delegating to
 * the engine's evaluator.
 *
 * <p>These tests assert the enforcer's integration contract, not the
 * engine's composition algebra (the engine has its own coverage).
 */
class RlsPolicyEnforcerTest {

    private static final String ALICE = "11111111-1111-1111-1111-111111111111";

    private static Principal claims(String userId, String projectId) {
        JwtClaims claims = JwtClaims.of(userId, projectId, "acme", "demo", "app_authenticated", "a@x.com");
        return new Principal(claims.role(), false, claims, Map.of());
    }

    /** Owner policy: a row is visible iff its user_id equals the caller. */
    private static Policy ownerSelect(String resource) {
        return new Policy(
                "id-" + resource, "owner-" + resource, resource,
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
    }

    private RlsPolicyEnforcer enforcerWith(String projectId, List<Policy> policies) {
        var provider = new InMemoryPolicyProvider();
        provider.put(projectId, policies);
        return new RlsPolicyEnforcer(provider);
    }

    @Test
    @DisplayName("no policies for the project → unrestricted passthrough")
    void noPolicies_unrestricted() {
        var enforcer = new RlsPolicyEnforcer(new InMemoryPolicyProvider());
        SqlFilter f = enforcer.filterFor("proj-a", "orders", claims(ALICE, "proj-a"), Operation.SELECT);
        assertThat(f.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("ALLOW policy for the table → parameterized WHERE fragment")
    void allowPolicy_producesFilterWithParams() {
        var enforcer = enforcerWith("proj-a", List.of(ownerSelect("orders")));
        SqlFilter f = enforcer.filterFor("proj-a", "orders", claims(ALICE, "proj-a"), Operation.SELECT);

        assertThat(f.isUnrestricted()).isFalse();
        assertThat(f.sql()).contains("user_id");
        assertThat(f.params()).isNotEmpty();
        assertThat(f.params()).containsValue(UUID.fromString(ALICE));
    }

    @Test
    @DisplayName("a role-assigned policy follows the role the request runs as, not the token's default role")
    void roleAssignedPolicy_followsTheActiveRole() {
        Policy editorsOwnRows = new Policy("id-e", "editor-own", "orders",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.role("editor")));
        var enforcer = enforcerWith("proj-a", List.of(editorsOwnRows));
        JwtClaims token = new JwtClaims(ALICE, "proj-a", "acme", "demo", "", "user", "a@x.com", null, 0L,
                Map.of(), Set.of("user", "editor"));

        SqlFilter asEditor = enforcer.filterFor("proj-a", "orders",
                new Principal("editor", false, token, Map.of()), Operation.SELECT);
        SqlFilter asUser = enforcer.filterFor("proj-a", "orders",
                new Principal("user", false, token, Map.of()), Operation.SELECT);

        assertThat(asEditor.sql()).contains("user_id");
        assertThat(asUser.sql()).doesNotContain("user_id");
    }

    @Test
    @DisplayName("an anon key token has no user: an INTEGER owner rule matches no rows and does not throw")
    void anonKeyToken_getsTheAnonymousContext() {
        Policy integerOwner = new Policy("id-i", "int-owner", "orders",
                PolicyEffect.ALLOW, Operation.ALL, LogicOperator.AND, 0, true,
                List.of(new Rule("owner_id", FieldType.INTEGER, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
        var enforcer = enforcerWith("proj-a", List.of(integerOwner));
        // What JwtService yields for a publishable key: no userId claim, so userId falls back to sub.
        JwtClaims anonKey = new JwtClaims("apikey:5", "proj-a", "acme", "demo", "", "anon", "apikey:5", "public", 5L,
                Map.of("sub", "apikey:5", "role", "anon", "scope", "public", "keyId", 5L));
        Principal anon = new Principal(Principal.ANON, false, anonKey, Map.of());

        SqlFilter filter = enforcer.filterFor("proj-a", "orders", anon, Operation.SELECT);

        assertThat(filter.params()).doesNotContainValue("apikey:5");
        assertThat(enforcer.permitsRow("proj-a", "orders", anon, Operation.INSERT, Map.of("owner_id", 5))).isFalse();
    }

    @Test
    @DisplayName("policy targets only a different table → our table is unrestricted")
    void policyForOtherTable_unrestrictedHere() {
        var enforcer = enforcerWith("proj-a", List.of(ownerSelect("invoices")));
        SqlFilter f = enforcer.filterFor("proj-a", "orders", claims(ALICE, "proj-a"), Operation.SELECT);
        assertThat(f.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("null claims (anonymous) builds a context without throwing")
    void nullClaims_noNpe() {
        var enforcer = enforcerWith("proj-a", List.of(ownerSelect("orders")));
        // Anonymous: currentUserId resolves to null → engine emits a predicate
        // that matches no rows (secure default), but must not throw.
        SqlFilter f = enforcer.filterFor("proj-a", "orders", null, Operation.SELECT);
        assertThat(f).isNotNull();
        assertThat(f.isUnrestricted()).isFalse();
    }

    @Test
    @DisplayName("policies are loaded for the request's project only")
    void projectScoped() {
        var provider = new InMemoryPolicyProvider();
        provider.put("proj-a", List.of(ownerSelect("orders")));
        // proj-b has no policies seeded.
        var enforcer = new RlsPolicyEnforcer(provider);

        assertThat(enforcer.filterFor("proj-a", "orders", claims(ALICE, "proj-a"), Operation.SELECT)
                .isUnrestricted()).isFalse();
        assertThat(enforcer.filterFor("proj-b", "orders", claims(ALICE, "proj-b"), Operation.SELECT)
                .isUnrestricted()).isTrue();
    }

    private static io.github.excalibase.rls.jdbc.SqlProjection project(
            RlsPolicyEnforcer enforcer, String projectId, String table, java.util.List<String> cols) {
        return enforcer.projectionFor(projectId, table, claims(ALICE, projectId), Operation.SELECT, cols);
    }

    @Test
    @DisplayName("HIDE column policy drops the column from the projection")
    void hideColumnDropsFromProjection() {
        var provider = new InMemoryPolicyProvider();
        provider.putColumns("proj-a", List.of(new io.github.excalibase.rls.ColumnPolicy(
                "c1", "hide-ssn", "users", java.util.Set.of("ssn"),
                Operation.ALL, io.github.excalibase.rls.MaskMode.HIDE,
                null, null, 0, true, List.of(Assignment.all()))));
        var enforcer = new RlsPolicyEnforcer(provider);

        var projection = project(enforcer, "proj-a", "users", List.of("id", "ssn", "name"));

        assertThat(projection.hidden()).contains("ssn");
        assertThat(projection.selectList()).noneMatch(s -> s.contains("ssn"));
        assertThat(projection.selectList()).anyMatch(s -> s.contains("id"));
    }

    @Test
    @DisplayName("no column policy → all requested columns pass through")
    void noColumnPolicy_passthrough() {
        var enforcer = new RlsPolicyEnforcer(new InMemoryPolicyProvider());
        var projection = project(enforcer, "proj-a", "users", List.of("id", "ssn"));
        assertThat(projection.hidden()).isEmpty();
        assertThat(projection.selectList()).hasSize(2);
    }

    @Test
    @DisplayName("operation is honored — INSERT-only policy doesn't filter a SELECT")
    void operationHonored() {
        Policy insertOnly = new Policy(
                "id-ins", "ins", "orders", PolicyEffect.ALLOW,
                java.util.Set.of(Operation.INSERT), LogicOperator.AND, 0, true,
                List.of(new Rule("user_id", FieldType.UUID, RuleOperator.EQ, "{{currentUserId}}")),
                List.of(Assignment.all()));
        var enforcer = enforcerWith("proj-a", List.of(insertOnly));

        SqlFilter sel = enforcer.filterFor("proj-a", "orders", claims(ALICE, "proj-a"), Operation.SELECT);
        assertThat(sel.isUnrestricted()).isTrue();
    }
}
