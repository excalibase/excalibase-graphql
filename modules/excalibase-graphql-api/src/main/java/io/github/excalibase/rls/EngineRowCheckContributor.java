package io.github.excalibase.rls;

import io.github.excalibase.security.Principal;
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
    private final Principal principal;

    public EngineRowCheckContributor(RlsPolicyEnforcer enforcer, String projectId, Principal principal) {
        this.enforcer = enforcer;
        this.projectId = projectId;
        this.principal = principal;
    }

    @Override
    public boolean permits(String tableName, Map<String, Object> row, RlsOp op) {
        return enforcer.permitsRow(projectId, tableName, principal, toOperation(op), row);
    }

    @Override
    public boolean permitsUpdate(String tableName, Map<String, Object> changedColumns) {
        return enforcer.permitsRowUpdate(projectId, tableName, principal, changedColumns);
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
