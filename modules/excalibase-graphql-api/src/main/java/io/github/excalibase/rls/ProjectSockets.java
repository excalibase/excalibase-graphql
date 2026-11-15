package io.github.excalibase.rls;

/** The open sockets of a project, as far as a permission change is concerned. */
public interface ProjectSockets {

    boolean hasSockets(String projectId);

    /** The project's current permission document is {@code version}: sockets opened under another one close. */
    void permissionsAre(String projectId, long version);

    /** Closes the project's sockets so their clients subscribe again under the current permissions. */
    void closeForPermissionChange(String projectId);
}
