package io.github.excalibase.security;

import io.github.excalibase.errors.DataError;
import io.github.excalibase.errors.DataErrorException;

/**
 * Provisioning says the project is paused: its database is shut down on purpose and comes back when the project
 * is resumed. Not cached as unknown and not a fault of the engine, so it is answered apart from an outage. It is a
 * {@link DataErrorException}, so a request that meets it while it runs (a pool that expired between the project
 * check and the query) is answered 503 {@code project_paused} on GraphQL and REST like any refused request.
 */
public class ProjectPausedException extends DataErrorException {

    public ProjectPausedException(String projectId) {
        super(DataError.projectPaused());
        this.projectId = projectId;
    }

    private final transient String projectId;

    public String projectId() {
        return projectId;
    }
}
