package io.github.excalibase.security;

/**
 * Provisioning says the project is paused: its database is shut down on purpose and comes back when the project
 * is resumed. Not cached as unknown and not a fault of the engine, so it is answered apart from an outage.
 */
public class ProjectPausedException extends RuntimeException {

    public ProjectPausedException(String projectId) {
        super("Project is paused: " + projectId);
    }
}
