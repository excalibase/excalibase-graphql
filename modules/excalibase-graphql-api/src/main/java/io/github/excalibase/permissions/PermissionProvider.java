package io.github.excalibase.permissions;

import io.github.excalibase.rls.ProjectCacheEvictor;

/**
 * Supplies a project's permission document (docs/features/permissions.md §8). Implementations never
 * answer with an empty set on failure: an empty set would read as "no permissions" for a project whose
 * permissions simply could not be fetched, and the caller must be told which of the two it is.
 */
public interface PermissionProvider extends ProjectCacheEvictor {

    /**
     * @throws io.github.excalibase.security.UnknownProjectException the project does not exist
     * @throws PermissionsUnavailableException the document could not be read and nothing is cached
     */
    PermissionSet permissionsFor(String projectId);

    /** False only for the stand-in used when no control plane is configured. */
    default boolean configured() {
        return true;
    }
}
