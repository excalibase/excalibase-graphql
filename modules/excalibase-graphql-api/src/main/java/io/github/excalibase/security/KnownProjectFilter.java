package io.github.excalibase.security;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.context.annotation.Value;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.excalibase.observability.requests.RequestMetrics;

/**
 * Refuses a request whose path names a project this deployment does not serve, and one whose path names no
 * project at all: the first path segment is the project, on every surface, and nothing is served without one
 * (the management port's actuator excepted). Both answers are the same 404. Runs whether or not authentication is on.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class KnownProjectFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(KnownProjectFilter.class);

    /** The project id, set once it passed the check: request metrics record only these. */
    public static final String KNOWN_PROJECT_ATTR = "excalibase.knownProject";
    /** Set on a request admitted as management, the one request that carries no project; later filters leave it be. */
    public static final String MANAGEMENT_ATTR = "excalibase.management";
    /** Set on a request the admin guard let through on the admin port: it names no project, and later filters leave it be. */
    public static final String ADMIN_ATTR = "excalibase.admin";
    public static final int ORDER = -100;

    private final KnownProjects knownProjects;
    private final boolean markKnownProjects;
    private final int managementPort;

    @Inject
    public KnownProjectFilter(KnownProjects knownProjects, @Nullable RequestMetrics requestMetrics,
                              @Value("${endpoints.all.port:-1}") int managementPort) {
        this(knownProjects, requestMetrics != null, managementPort);
    }

    /** {@code markKnownProjects} only when request metrics are on, so the hot path is unchanged when off. */
    public KnownProjectFilter(KnownProjects knownProjects, boolean markKnownProjects, int managementPort) {
        this.knownProjects = knownProjects;
        this.markKnownProjects = markKnownProjects;
        this.managementPort = managementPort;
    }

    /** A filter with no management port: nothing is exempt from the project check. */
    public KnownProjectFilter(KnownProjects knownProjects, boolean markKnownProjects) {
        this(knownProjects, markKnownProjects, -1);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        if (request.getAttributes().contains(ADMIN_ATTR)) {
            return null;
        }
        boolean actuator = isActuatorPath(request.getPath());
        if (actuator && isOnManagementPort(request)) {
            request.getAttributes().put(MANAGEMENT_ATTR, Boolean.TRUE);
            return null;
        }
        String projectId = ProjectPath.leadingSegment(request.getPath());
        // "actuator" is not a project: off the management port it is refused, never looked up in provisioning.
        if (projectId == null || actuator) {
            return FilterErrors.write(HttpStatus.NOT_FOUND, "Project not found", "project_not_found");
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
        } catch (ProjectPausedException e) {
            return FilterErrors.write(HttpStatus.SERVICE_UNAVAILABLE, "Project paused", "project_paused");
        } catch (VaultCredentialException e) {
            log.error("project_lookup_failed project={}", projectId, e);
            return FilterErrors.write(HttpStatus.SERVICE_UNAVAILABLE, "Project unavailable", "project_unavailable");
        }
        if (markKnownProjects) {
            request.getAttributes().put(KNOWN_PROJECT_ATTR, projectId);
        }
        return null;
    }

    private static boolean isActuatorPath(String path) {
        return path.equals("/actuator") || path.startsWith("/actuator/");
    }

    private boolean isOnManagementPort(HttpRequest<?> request) {
        InetSocketAddress server = request.getServerAddress();
        return managementPort > 0 && server != null && server.getPort() == managementPort;
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
            boolean bare = !uri.isOpaque() && uri.getRawUserInfo() == null && "".equals(uri.getRawPath())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
            return webScheme && bare && uri.getHost() != null ? Optional.of(origin) : Optional.empty();
        } catch (URISyntaxException | RuntimeException _) {
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
