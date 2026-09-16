package io.github.excalibase.rls;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-process {@link PolicyProvider} backed by a map keyed
 * on projectId. Used as the default provider until a remote (HTTP +
 * NATS-invalidated) source is wired, and as the seam tests inject
 * policies through.
 *
 * <p>The {@link #put} method is also the write side a NATS consumer
 * calls when the provisioning service signals a change, so reads and
 * writes are concurrency-safe.
 */
public final class InMemoryPolicyProvider implements PolicyProvider {

    private final Map<String, List<Policy>> byProject = new ConcurrentHashMap<>();
    private final Map<String, List<ColumnPolicy>> columnsByProject = new ConcurrentHashMap<>();
    private final Map<String, List<TableGrant>> grantsByProject = new ConcurrentHashMap<>();

    /** Replaces the row-policy set for {@code projectId}. */
    public void put(String projectId, List<Policy> policies) {
        byProject.put(projectId, List.copyOf(policies));
    }

    /** Replaces the column-policy set for {@code projectId}. */
    public void putColumns(String projectId, List<ColumnPolicy> columnPolicies) {
        columnsByProject.put(projectId, List.copyOf(columnPolicies));
    }

    /** Replaces the table grants for {@code projectId}. */
    public void putGrants(String projectId, List<TableGrant> grants) {
        grantsByProject.put(projectId, List.copyOf(grants));
    }

    /** Drops all policies (row, column and grants) for {@code projectId}. */
    public void evict(String projectId) {
        byProject.remove(projectId);
        columnsByProject.remove(projectId);
        grantsByProject.remove(projectId);
    }

    @Override
    public List<Policy> policiesFor(String projectId) {
        if (projectId == null) {
            return List.of();
        }
        return byProject.getOrDefault(projectId, List.of());
    }

    @Override
    public List<ColumnPolicy> columnPoliciesFor(String projectId) {
        if (projectId == null) {
            return List.of();
        }
        return columnsByProject.getOrDefault(projectId, List.of());
    }

    @Override
    public List<TableGrant> grantsFor(String projectId) {
        if (projectId == null) {
            return List.of();
        }
        return grantsByProject.getOrDefault(projectId, List.of());
    }
}
