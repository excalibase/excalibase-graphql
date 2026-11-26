package io.github.excalibase.permissions;

import io.github.excalibase.rls.ProjectCacheEvictor;

import java.util.function.ObjLongConsumer;

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

    /**
     * Asks to be told when a refresh replaces a held document with a different one: the project and the new
     * version. A refresh that follows an eviction is not reported, the eviction is the signal there.
     */
    default void onChange(ObjLongConsumer<String> listener) {
    }

    /** Forgets every project's held document, as {@link #evict} does one's. */
    default void evictAll() {
    }

    /** False only for the stand-in used when no control plane is configured. */
    default boolean configured() {
        return true;
    }
}
