package io.github.excalibase.rls;

import io.github.excalibase.rls.jdbc.JdbcEvaluator;
import io.github.excalibase.rls.jdbc.SqlFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtClaimsUserContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a request — (projectId, table, JWT claims, operation) — into a
 * parameterized RLS {@link SqlFilter} by loading the project's policies
 * and delegating to the engine's {@link JdbcEvaluator}.
 *
 * <p>This is the application-layer, query-first RLS seam: callers splice
 * {@link SqlFilter#composeWhere(String)} into their SELECT and merge
 * {@link SqlFilter#params()}. It is backend-agnostic — the same path
 * serves Postgres, MySQL, etc., independent of native database RLS.
 *
 * <p>When the project has no policies for the (table, operation) pair the
 * engine returns {@link SqlFilter#UNRESTRICTED} (empty), so wiring this in
 * is a no-op until an operator authors a policy — safe to enable globally.
 */
public final class RlsPolicyEnforcer {

    private final PolicyProvider policyProvider;

    public RlsPolicyEnforcer(PolicyProvider policyProvider) {
        this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider");
    }

    /**
     * Compiles the RLS filter for one table. {@code claims} may be null
     * for anonymous traffic — an anonymous context (null user, project as
     * tenant) is used, which the engine resolves to a no-rows predicate
     * for owner-style policies rather than throwing.
     */
    public SqlFilter filterFor(String projectId, String table, JwtClaims claims, Operation op) {
        List<Policy> policies = policyProvider.policiesFor(projectId);
        UserContext ctx = claims != null
                ? new JwtClaimsUserContext(claims)
                : anonymous(projectId);
        return new JdbcEvaluator(policies).compile(table, ctx, op);
    }

    /** Anonymous principal: no user id, project doubles as tenant, no roles. */
    private static UserContext anonymous(String projectId) {
        return new UserContext() {
            @Override public String userId() { return null; }
            @Override public String tenantId() { return projectId; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public Set<String> groupIds() { return Set.of(); }
        };
    }
}
