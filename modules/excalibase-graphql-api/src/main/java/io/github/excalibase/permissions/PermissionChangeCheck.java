package io.github.excalibase.permissions;

import io.github.excalibase.security.UnknownProjectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells whether a project's permission document is the one it was before an invalidation. {@link #remember}
 * takes the document held now; after the caches are dropped, {@link #changed} reads it back and compares. When
 * there was nothing to compare with, or the read fails, the answer is yes: the caller errs on the side of
 * acting on a change.
 */
public final class PermissionChangeCheck {

    private static final Logger log = LoggerFactory.getLogger(PermissionChangeCheck.class);

    private final PermissionProvider permissions;
    private final Map<String, PermissionSet> remembered = new ConcurrentHashMap<>();

    public PermissionChangeCheck(PermissionProvider permissions) {
        this.permissions = permissions;
    }

    /** Keeps the document held for the project now, if any. */
    public void remember(String projectId) {
        Optional<PermissionSet> held = permissions.cached(projectId);
        held.ifPresentOrElse(document -> remembered.put(projectId, document), () -> remembered.remove(projectId));
    }

    /** Reads the document again and compares it with the remembered one; the remembered one is used up. */
    public boolean changed(String projectId) {
        PermissionSet before = remembered.remove(projectId);
        try {
            PermissionSet after = permissions.permissionsFor(projectId);
            return before == null || !before.equals(after);
        } catch (PermissionsUnavailableException | UnknownProjectException e) {
            log.warn("Permissions of project {} cannot be read back, treating them as changed: {}", projectId,
                    e.getMessage());
            return true;
        }
    }
}
