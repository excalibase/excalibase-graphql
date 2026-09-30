package io.github.excalibase.permissions;

/**
 * Stands in when no control plane is configured ({@code app.security.rls.policy-url} blank). Every read
 * is refused: what a deployment without a permission source serves is decided where permissions are
 * enforced, not by pretending an empty document was fetched.
 */
public final class NoPermissionSource implements PermissionProvider {

    @Override
    public PermissionSet permissionsFor(String projectId) {
        throw new IllegalStateException("no permission source");
    }

    @Override
    public void evict(String projectId) {
        // nothing is cached
    }
}
