package io.github.excalibase.rls;

import java.util.List;

/**
 * Supplies the row-level-security policies in force for a project.
 *
 * <p>Policies are authored + stored in the provisioning service
 * (EXC-318) and pushed to this process; a {@code PolicyProvider} is the
 * read seam the RLS enforcer consults at query time. Implementations
 * must be cheap to call on the hot path (cache, not network) and must
 * never return {@code null}.
 */
public interface PolicyProvider {

    /**
     * Returns the row-level policies scoped to {@code projectId}, or an empty
     * list when the project is unknown / has no policies. Never {@code null}.
     */
    List<Policy> policiesFor(String projectId);

    /**
     * Returns the column-level (masking) policies scoped to {@code projectId},
     * or an empty list. Never {@code null}. Defaults to empty so providers that
     * only serve row policies stay source-compatible.
     */
    default List<ColumnPolicy> columnPoliciesFor(String projectId) {
        return List.of();
    }

    /**
     * Drops any cached policies for {@code projectId} so the next read re-fetches.
     * The NATS policy-change subscriber calls this for immediate convergence;
     * the per-project TTL remains the fail-safe. Defaults to a no-op for providers
     * that hold no cache, so eviction is always a safe call.
     */
    default void evict(String projectId) {
        // no-op: providers without a cache converge via re-read on every call
    }
}
