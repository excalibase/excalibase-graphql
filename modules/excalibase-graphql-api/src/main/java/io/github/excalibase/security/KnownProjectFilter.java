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

    private final KnownProjects knownProjects;

    public KnownProjectFilter(KnownProjects knownProjects) {
        this.knownProjects = knownProjects;
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
        } catch (VaultCredentialException e) {
            log.error("project_lookup_failed project={}", projectId, e);
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Project unavailable",
                    "project_unavailable");
            return;
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
