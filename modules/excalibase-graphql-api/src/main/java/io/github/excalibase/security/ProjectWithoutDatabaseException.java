package io.github.excalibase.security;

/**
 * The project exists but was created without a database, so there is nothing
 * to serve GraphQL or REST from. Not cached as unknown: a database can be
 * added to the project later.
 */
public class ProjectWithoutDatabaseException extends RuntimeException {

    public ProjectWithoutDatabaseException(String projectId) {
        super("Project has no database: " + projectId);
    }
}
