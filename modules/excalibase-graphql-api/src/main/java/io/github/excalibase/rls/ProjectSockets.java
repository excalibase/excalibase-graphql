package io.github.excalibase.rls;

/** The open sockets of a project, as far as a permission change is concerned. */
public interface ProjectSockets {

    boolean hasSockets(String projectId);

    /** Closes the project's sockets so their clients subscribe again under the current permissions. */
    void closeForPermissionChange(String projectId);
}
