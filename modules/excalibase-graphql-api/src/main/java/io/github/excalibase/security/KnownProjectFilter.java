package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.excalibase.observability.requests.RequestMetrics;

/**
 * Refuses a request whose path names a project this deployment does not serve.
 * Runs on every project-scoped route — GraphQL, REST and both WebSocket
 * upgrades — whether or not authentication is on.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class KnownProjectFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(KnownProjectFilter.class);

    /** The project id, set once it passed the check: request metrics record only these. */
    public static final String KNOWN_PROJECT_ATTR = "excalibase.knownProject";
    public static final int ORDER = -100;

    private final KnownProjects knownProjects;
    private final boolean markKnownProjects;

    @Inject
    public KnownProjectFilter(KnownProjects knownProjects, @Nullable RequestMetrics requestMetrics) {
        this(knownProjects, requestMetrics != null);
    }

    /** {@code markKnownProjects} only when request metrics are on, so the hot path is unchanged when off. */
    public KnownProjectFilter(KnownProjects knownProjects, boolean markKnownProjects) {
        this.knownProjects = knownProjects;
        this.markKnownProjects = markKnownProjects;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String projectId = ProjectPath.fromUri(request.getPath());
        if (projectId == null) {
            return null;
        }
        try {
            knownProjects.require(projectId);
        } catch (UnknownProjectException e) {
            return FilterErrors.write(HttpStatus.NOT_FOUND, "Project not found", "project_not_found");
        } catch (ProjectWithoutDatabaseException e) {
            return FilterErrors.write(HttpStatus.CONFLICT, "Project has no database", "project_has_no_database");
        } catch (VaultCredentialException e) {
            log.error("project_lookup_failed project={}", projectId, e);
            return FilterErrors.write(HttpStatus.SERVICE_UNAVAILABLE, "Project unavailable", "project_unavailable");
        }
        if (markKnownProjects) {
            request.getAttributes().put(KNOWN_PROJECT_ATTR, projectId);
        }
        return null;
    }
}
