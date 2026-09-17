package io.github.excalibase.rls;

/**
 * Anything holding per-project state that a control-plane change invalidates —
 * the policy cache and the built engine schemas. One signal, one call per holder,
 * so a new cache cannot be added without being wired into invalidation.
 */
public interface ProjectCacheEvictor {

    void evict(String projectId);
}
