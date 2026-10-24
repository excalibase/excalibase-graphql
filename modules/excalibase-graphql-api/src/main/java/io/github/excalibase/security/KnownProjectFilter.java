package io.github.excalibase.security;

import io.github.excalibase.service.VaultCredentialException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static void writeError(HttpServletResponse response, int status, String message, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"errors\":[{\"message\":\"" + message
                + "\",\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }
}
