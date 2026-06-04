package io.github.excalibase.rls;

import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RowCheckContributor;

import java.util.Map;

/**
 * Request-scoped {@link RowCheckContributor} backing the WITH-CHECK half of RLS
 * with the engine's RowMatcher (via {@link RlsPolicyEnforcer#permitsRow}).
 * Registered on {@code RlsContext} per request; the mutation compiler consults
 * it before emitting an INSERT so a row the caller may not create is rejected
 * before any SQL runs.
 */
public final class EngineRowCheckContributor implements RowCheckContributor {

    private final RlsPolicyEnforcer enforcer;
    private final String projectId;
    private final JwtClaims claims;

    public EngineRowCheckContributor(RlsPolicyEnforcer enforcer, String projectId, JwtClaims claims) {
        this.enforcer = enforcer;
        this.projectId = projectId;
        this.claims = claims;
    }

    @Override
    public boolean permits(String tableName, Map<String, Object> row, RlsOp op) {
        return enforcer.permitsRow(projectId, tableName, claims, toOperation(op), row);
    }

    private static Operation toOperation(RlsOp op) {
        return switch (op) {
            case SELECT -> Operation.SELECT;
            case INSERT -> Operation.INSERT;
            case UPDATE -> Operation.UPDATE;
            case DELETE -> Operation.DELETE;
        };
    }
}
