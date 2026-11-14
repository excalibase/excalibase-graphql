package io.github.excalibase.security;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.service.VaultCredentialException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses a request whose path names a project this deployment does not serve.
 * Runs on every project-scoped route — GraphQL, REST and both WebSocket
 * upgrades — whether or not authentication is on.
 */
public class KnownProjectFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KnownProjectFilter.class);

    /** The project id, set once it passed the check: request metrics record only these. */
    public static final String KNOWN_PROJECT_ATTR = "excalibase.knownProject";

    private final KnownProjects knownProjects;
    private final boolean markKnownProjects;

    public KnownProjectFilter(KnownProjects knownProjects) {
        this(knownProjects, false);
    }

    /** {@code markKnownProjects} only when request metrics are on, so the hot path is unchanged when off. */
    public KnownProjectFilter(KnownProjects knownProjects, boolean markKnownProjects) {
        this.knownProjects = knownProjects;
        this.markKnownProjects = markKnownProjects;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String projectId = ProjectPath.fromUri(request.getRequestURI());
        if (projectId == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            knownProjects.require(projectId);
        } catch (UnknownProjectException e) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "Project not found", "project_not_found");
            return;
        } catch (ProjectLookupThrottledException e) {
            if (CorsUtils.isPreFlightRequest(request)) {
                answerPreflight(request, response);
                return;
            }
            allowAnyOriginToRead(request, response);
            writeError(response, 429, "Too many project lookups", "project_lookup_throttled");
            return;
        } catch (ProjectWithoutDatabaseException e) {
            writeError(response, HttpServletResponse.SC_CONFLICT, "Project has no database", "project_has_no_database");
            return;
        } catch (VaultCredentialException e) {
            log.error("project_lookup_failed project={}", projectId, e);
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Project unavailable",
                    "project_unavailable");
            return;
        }
        if (markKnownProjects) {
            request.setAttribute(KNOWN_PROJECT_ATTR, projectId);
        }
        chain.doFilter(request, response);
    }

    /**
     * This runs before the CORS filter, which would have to ask provisioning for the project's origins, and
     * the throttle exists to keep that from happening. The refusal says nothing about any project, so any origin
     * may read it: a browser then sees a 429 it can back off from instead of an opaque network error.
     */
    private static void allowAnyOriginToRead(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Vary", "Origin");
        if (request.getHeader("Origin") != null) {
            response.setHeader("Access-Control-Allow-Origin", "*");
        }
    }

    /**
     * A preflight for a project whose lookup is throttled is answered here, never passed on: the CORS filter would
     * fetch the project's origins from provisioning, once per preflight, for an id nobody has vouched for. Any
     * origin may then send the real request, which gets the readable 429 (or its real answer).
     */
    private static void answerPreflight(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        response.addHeader("Vary", "Origin");
        response.addHeader("Vary", "Access-Control-Request-Method");
        response.addHeader("Vary", "Access-Control-Request-Headers");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", String.join(",", ProjectCorsConfigurationSource.ALLOWED_METHODS));
        String requested = request.getHeader("Access-Control-Request-Headers");
        if (requested != null) {
            response.setHeader("Access-Control-Allow-Headers", requested);
        }
        response.setHeader("Access-Control-Max-Age", "0");
    }

    private static void writeError(HttpServletResponse response, int status, String message, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"errors\":[{\"message\":\"" + message
                + "\",\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }
}
