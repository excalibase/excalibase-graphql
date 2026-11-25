package io.github.excalibase.permissions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalLong;

/** Reads the version of a project's current permission document, for callers that must not name the model. */
public final class PermissionVersions {

    private static final Logger log = LoggerFactory.getLogger(PermissionVersions.class);

    private final PermissionProvider permissions;

    public PermissionVersions(PermissionProvider permissions) {
        this.permissions = permissions;
    }

    /** The version read now, or empty when the document cannot be read for any reason. */
    public OptionalLong current(String projectId) {
        try {
            return OptionalLong.of(permissions.permissionsFor(projectId).version());
        } catch (RuntimeException e) {
            log.warn("Permissions of project {} cannot be read: {}", projectId, e.toString());
            return OptionalLong.empty();
        }
    }
}
