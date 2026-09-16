package io.github.excalibase.rls;

import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.TableGrantContributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped {@link TableGrantContributor} that asks the
 * {@link RlsPolicyEnforcer} whether a table is exposed to this caller at all.
 * Registered on {@code RlsContext} next to the row and column contributors, and
 * consulted by the compiler before either of them.
 *
 * <p>Decisions are memoised per {@code table:operation} so a query touching the
 * same table several times pays the check once.
 */
public final class EngineTableGrantContributor implements TableGrantContributor {

    private final RlsPolicyEnforcer enforcer;
    private final String projectId;
    private final JwtClaims claims;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    public EngineTableGrantContributor(RlsPolicyEnforcer enforcer, String projectId, JwtClaims claims) {
        this.enforcer = enforcer;
        this.projectId = projectId;
        this.claims = claims;
    }

    @Override
    public boolean permits(String tableName, RlsOp op) {
        return cache.computeIfAbsent(tableName + ":" + op.name(),
                _ -> enforcer.permitsTable(projectId, tableName, claims, toOperation(op)));
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
