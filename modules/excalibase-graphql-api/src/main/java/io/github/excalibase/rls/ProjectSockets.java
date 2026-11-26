package io.github.excalibase.rls;

import java.util.Set;

/** The open sockets of a project, as far as a permission change is concerned. */
public interface ProjectSockets {

    boolean hasSockets(String projectId);

    /** The projects that have an open socket now. */
    Set<String> projectsWithSockets();

    /** The project's current permission document is {@code version}: sockets opened under another one close. */
    void permissionsAre(String projectId, long version);

    /** Closes the project's sockets so their clients subscribe again under the current permissions. */
    void closeForPermissionChange(String projectId);
}
