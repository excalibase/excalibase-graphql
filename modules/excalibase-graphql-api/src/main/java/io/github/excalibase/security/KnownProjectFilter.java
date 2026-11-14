package io.github.excalibase.security;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
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
        } catch (ProjectLookupThrottledException e) {
            if (isPreflight(request)) {
                return answerPreflight(request);
            }
            return readableByAskingOrigin(request,
                    FilterErrors.write(HttpStatus.TOO_MANY_REQUESTS, "Too many project lookups", "project_lookup_throttled"));
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

    private static boolean isPreflight(HttpRequest<?> request) {
        return request.getMethod() == HttpMethod.OPTIONS
                && request.getHeaders().contains("Origin")
                && request.getHeaders().contains("Access-Control-Request-Method");
    }

    /**
     * This runs before the CORS filter, which would have to ask provisioning for the project's origins, and the
     * throttle exists to keep that from happening. The refusal says nothing about any project, so the asking origin
     * may read it: a browser then sees a 429 it can back off from instead of an opaque network error.
     */
    private static HttpResponse<String> readableByAskingOrigin(HttpRequest<?> request, HttpResponse<String> refusal) {
        MutableHttpResponse<String> response = (MutableHttpResponse<String>) refusal;
        response.header("Vary", "Origin");
        validOrigin(request).ifPresent(origin -> response.header("Access-Control-Allow-Origin", origin));
        return response;
    }

    /** The request's Origin when it is exactly scheme, host and optional port; anything else is not echoed. */
    private static Optional<String> validOrigin(HttpRequest<?> request) {
        String origin = request.getHeaders().get("Origin");
        if (origin == null || origin.chars().anyMatch(c -> c <= ' ' || c >= 0x7f)) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(origin);
            boolean webScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            boolean bare = uri.getRawUserInfo() == null && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            return webScheme && bare && uri.getHost() != null ? Optional.of(origin) : Optional.empty();
        } catch (URISyntaxException _) {
            return Optional.empty();
        }
    }

    /**
     * A preflight for a project whose lookup is throttled is answered here, never passed on: the CORS filter would
     * fetch the project's origins from provisioning, once per preflight, for an id nobody has vouched for. The
     * asking origin may then send the real request, which gets the readable 429 (or its real answer).
     */
    private static HttpResponse<?> answerPreflight(HttpRequest<?> request) {
        MutableHttpResponse<?> response = HttpResponse.noContent()
                .header("Vary", "Origin")
                .header("Access-Control-Allow-Methods", String.join(",", ProjectCorsConfigurationSource.ALLOWED_METHODS))
                .header("Access-Control-Max-Age", "0");
        validOrigin(request).ifPresent(origin -> response.header("Access-Control-Allow-Origin", origin));
        String requested = request.getHeaders().get("Access-Control-Request-Headers");
        if (requested != null) {
            response.header("Access-Control-Allow-Headers", requested);
        }
        return response;
    }
}
