package io.github.excalibase.rls;

import io.github.excalibase.rls.jdbc.SqlFilter;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Request-scoped {@link RlsWhereContributor} that asks the {@link RlsPolicyEnforcer}
 * for the SELECT filter on each table the compiler is building a WHERE for, then
 * hands back a parameter-namespaced predicate the compiler can splice directly.
 *
 * <p>One instance is created per request (it carries that request's project +
 * claims) and registered on {@code RlsContext}. It is single-threaded with
 * respect to a request but uses an {@link AtomicInteger} so the namespacing is
 * robust even if the compiler parallelizes table compilation in future.
 *
 * <p><b>Param namespacing.</b> The engine emits fixed keys ({@code rls_p0,
 * rls_p1, …}) per {@code compile()} call. A single GraphQL query can trigger
 * several contributions (e.g. a connection query compiles the records CTE and
 * the totalCount CTE, and multi-table queries compile each table). To keep every
 * key unique in the shared param map, each contribution is prefixed with a
 * per-instance counter: {@code rls_p0 → rls_c0_rls_p0}. Keys are renamed
 * longest-first so a shorter key is never rewritten inside a longer one
 * (e.g. {@code :rls_p1} must not corrupt {@code :rls_p10}).
 */
public final class EngineRlsWhereContributor implements RlsWhereContributor {

    private final RlsPolicyEnforcer enforcer;
    private final String projectId;
    private final JwtClaims claims;
    private final AtomicInteger counter = new AtomicInteger();

    public EngineRlsWhereContributor(RlsPolicyEnforcer enforcer, String projectId, JwtClaims claims) {
        this.enforcer = enforcer;
        this.projectId = projectId;
        this.claims = claims;
    }

    @Override
    public Contribution contribute(String tableName, RlsOp op) {
        SqlFilter filter = enforcer.filterFor(projectId, tableName, claims, toOperation(op));
        if (filter.isUnrestricted()) {
            return null;
        }
        return namespace(filter, "rls_c" + counter.getAndIncrement() + "_");
    }

    private static Operation toOperation(RlsOp op) {
        return switch (op) {
            case SELECT -> Operation.SELECT;
            case INSERT -> Operation.INSERT;
            case UPDATE -> Operation.UPDATE;
            case DELETE -> Operation.DELETE;
        };
    }

    /** Re-keys the filter's params with {@code prefix}, rewriting the SQL to match. */
    private static Contribution namespace(SqlFilter filter, String prefix) {
        String sql = filter.sql();
        Map<String, Object> params = new HashMap<>();

        List<String> keys = new ArrayList<>(filter.params().keySet());
        // Longest key first: prevents ":rls_p1" from matching inside ":rls_p10".
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String key : keys) {
            String renamed = prefix + key;
            sql = sql.replace(":" + key, ":" + renamed);
            params.put(renamed, filter.params().get(key));
        }
        return new Contribution(sql, params);
    }
}
