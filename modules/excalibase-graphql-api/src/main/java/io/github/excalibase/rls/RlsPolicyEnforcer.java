package io.github.excalibase.rls;

import io.github.excalibase.rls.jdbc.JdbcEvaluator;
import io.github.excalibase.rls.jdbc.QuoteStyle;
import io.github.excalibase.rls.jdbc.SqlFilter;
import io.github.excalibase.rls.jdbc.SqlProjection;
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
    private final QuoteStyle quoteStyle;

    public RlsPolicyEnforcer(PolicyProvider policyProvider) {
        this(policyProvider, QuoteStyle.ANSI);
    }

    /**
     * @param quoteStyle identifier quoting for emitted RLS SQL — {@link
     *                   QuoteStyle#BACKTICK} for MySQL, {@link QuoteStyle#ANSI}
     *                   (default) for Postgres. Wrong quoting silently mis-filters,
     *                   so this is keyed off the deployment's database type.
     */
    public RlsPolicyEnforcer(PolicyProvider policyProvider, QuoteStyle quoteStyle) {
        this.policyProvider = Objects.requireNonNull(policyProvider, "policyProvider");
        this.quoteStyle = (quoteStyle == null) ? QuoteStyle.ANSI : quoteStyle;
    }

    /**
     * Compiles the RLS filter for one table. {@code claims} may be null
     * for anonymous traffic — an anonymous context (null user, project as
     * tenant) is used, which the engine resolves to a no-rows predicate
     * for owner-style policies rather than throwing.
     */
    public SqlFilter filterFor(String projectId, String table, JwtClaims claims, Operation op) {
        return evaluator(projectId).compile(table, context(projectId, claims), op);
    }

    /**
     * Compiles the column-level projection for one table: which of
     * {@code requestedColumns} are visible, masked, or hidden for this caller.
     * Hidden columns appear in {@link SqlProjection#hidden()} and are absent
     * from its select list.
     */
    public SqlProjection projectionFor(String projectId, String table, JwtClaims claims,
                                       Operation op, List<String> requestedColumns) {
        return evaluator(projectId).project(table, context(projectId, claims), op, requestedColumns);
    }

    /**
     * WITH-CHECK validation for a candidate row (INSERT, or an UPDATE's new
     * image): returns {@code true} iff the row satisfies the project's row
     * policies for {@code op}, using the engine's in-memory {@link RowMatcher}.
     * When no ALLOW policy targets the resource/op, the matcher's default-deny
     * semantics apply.
     */
    public boolean permitsRow(String projectId, String table, JwtClaims claims,
                              Operation op, java.util.Map<String, Object> row) {
        return new RowMatcher(policyProvider.policiesFor(projectId))
                .matches(table, row, context(projectId, claims), op);
    }

    /**
     * Applies column masking to an already-fetched row in place — the non-SQL
     * path used by realtime fan-out, where rows arrive from CDC rather than a
     * SELECT. Hidden columns are removed and NULL-masked columns nulled, per the
     * caller's column policies. Returns the same map for chaining.
     */
    public java.util.Map<String, Object> maskRow(String projectId, String resource, JwtClaims claims,
                                                  Operation op, java.util.Map<String, Object> row) {
        new ColumnMasker(policyProvider.columnPoliciesFor(projectId))
                .plan(resource, context(projectId, claims), op)
                .apply(row);
        return row;
    }

    /** One evaluator per call, carrying both the row and column policies for the project. */
    private JdbcEvaluator evaluator(String projectId) {
        return new JdbcEvaluator(
                policyProvider.policiesFor(projectId),
                policyProvider.columnPoliciesFor(projectId),
                quoteStyle);
    }

    private UserContext context(String projectId, JwtClaims claims) {
        return claims != null ? new JwtClaimsUserContext(claims) : anonymous(projectId);
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
