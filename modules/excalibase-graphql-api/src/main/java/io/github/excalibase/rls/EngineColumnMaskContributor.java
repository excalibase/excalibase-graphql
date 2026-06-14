package io.github.excalibase.rls;

import io.github.excalibase.rls.jdbc.SqlProjection;
import io.github.excalibase.security.ColumnMaskContributor;
import io.github.excalibase.security.JwtClaims;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped {@link ColumnMaskContributor} that decides per-column
 * visibility by asking the {@link RlsPolicyEnforcer} to project a single
 * column for this caller. Registered on {@code RlsContext} for the duration
 * of a request (it carries that request's project + claims).
 *
 * <p>Decisions are memoised per {@code table.column} so a query that selects
 * the same column across many rows/sub-selects pays the engine cost once.
 */
public final class EngineColumnMaskContributor implements ColumnMaskContributor {

    private final RlsPolicyEnforcer enforcer;
    private final String projectId;
    private final JwtClaims claims;
    private final Map<String, Decision> cache = new ConcurrentHashMap<>();

    public EngineColumnMaskContributor(RlsPolicyEnforcer enforcer, String projectId, JwtClaims claims) {
        this.enforcer = enforcer;
        this.projectId = projectId;
        this.claims = claims;
    }

    @Override
    public Decision decide(String tableName, String columnName) {
        return cache.computeIfAbsent(tableName + "." + columnName, _ -> compute(tableName, columnName));
    }

    private Decision compute(String tableName, String columnName) {
        SqlProjection projection = enforcer.projectionFor(
                projectId, tableName, claims, Operation.SELECT, List.of(columnName));
        if (projection.hidden().contains(columnName)) {
            return Decision.HIDDEN;
        }
        // NULL mask renders as "NULL AS <col>" in the select list; HIDE never appears.
        boolean nulled = projection.selectList().stream()
                .anyMatch(expr -> expr.toUpperCase(Locale.ROOT).startsWith("NULL AS"));
        return nulled ? Decision.NULLED : Decision.VISIBLE;
    }
}
