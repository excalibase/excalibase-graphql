package io.github.excalibase.security;

import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.rls.EngineColumnMaskContributor;
import io.github.excalibase.rls.EngineRlsWhereContributor;
import io.github.excalibase.rls.EngineRowCheckContributor;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String JWT_CLAIMS_ATTR = SecurityConstants.JWT_CLAIMS_ATTR;

    /** Code reported when the URL project and the token project disagree. */
    private static final String PROJECT_MISMATCH_CODE = "project_mismatch";

    private final JwtService jwtService;
    private final RlsPolicyEnforcer rlsEnforcer;

    public JwtAuthFilter(JwtService jwtService) {
        this(jwtService, null);
    }

    public JwtAuthFilter(JwtService jwtService, RlsPolicyEnforcer rlsEnforcer) {
        this.jwtService = jwtService;
        this.rlsEnforcer = rlsEnforcer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // The project comes from the URL path only, already matched to a project this
        // deployment serves. A token cannot name one: when it carries a project, it
        // must agree with the path.
        String pathProjectId = extractProjectId(request);

        JwtClaims claims;
        try {
            claims = verifyClaims(request, pathProjectId);
        } catch (JwtVerificationException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token", e.code());
            return;
        }

        if (pathProjectId != null && claims != null && claims.projectId() != null
                && !pathProjectId.equals(claims.projectId())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "URL project does not match token project",
                    PROJECT_MISMATCH_CODE);
            return;
        }
        Principal principal;
        try {
            principal = RoleResolver.resolve(claims, request.getHeader(RoleResolver.ROLE_HEADER),
                    excalibaseHeaders(request));
        } catch (RoleNotAllowedException e) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Role not allowed", e.code());
            return;
        }
        request.setAttribute(SecurityConstants.PRINCIPAL_ATTR, principal);
        try {
            applyTenantContext(pathProjectId, claims);
            applyRlsContext(pathProjectId, principal);
            chain.doFilter(request, response);
        } finally {
            RlsContext.clear();
            clearTenantContext(pathProjectId);
        }
    }

    /**
     * Project id from a project-scoped path — {@code /{projectId}/graphql} or
     * {@code /{projectId}/api/v1/…} — or {@code null} for a route with no project
     * (health, actuator). The projectId is a single opaque segment
     * (e.g. {@code proj-237qoqksdb}).
     */
    static String extractProjectId(HttpServletRequest request) {
        return ProjectPath.fromUri(request.getRequestURI());
    }

    /**
     * Registers the query-first RLS contributors for this request. A no-op when
     * the engine isn't wired, the request carries no project context, or the
     * request runs as {@code service}, which row and column policies do not govern.
     */
    private void applyRlsContext(String projectId, Principal principal) {
        if (rlsEnforcer == null || projectId == null || principal.bypass()) {
            return;
        }
        // An anonymous principal gets the engine's anonymous context, so owner/claim
        // policies match no rows (fail-closed). RLS applies on every request, not only with a token.
        RlsContext.set(new EngineRlsWhereContributor(rlsEnforcer, projectId, principal));
        RlsContext.setColumnMask(new EngineColumnMaskContributor(rlsEnforcer, projectId, principal));
        RlsContext.setRowCheck(new EngineRowCheckContributor(rlsEnforcer, projectId, principal));
    }

    /** The request's {@code x-excalibase-*} headers, which carry session variables for a service token. */
    private static Map<String, String> excalibaseHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        for (String name : Collections.list(names)) {
            if (name.toLowerCase(Locale.ROOT).startsWith(Principal.SESSION_VARIABLE_PREFIX)) {
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    private JwtClaims verifyClaims(HttpServletRequest request, String pathProjectId) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        // EXC-11: the path project is what the audience must cover.
        JwtClaims claims = jwtService.verify(authHeader.substring(7), pathProjectId);
        request.setAttribute(JWT_CLAIMS_ATTR, claims);
        return claims;
    }

    private static void applyTenantContext(String projectId, JwtClaims claims) {
        if (projectId == null) {
            return;
        }
        String orgSlug = claims != null ? claims.orgSlug() : null;
        TenantContext.setTenantId(projectId);
        TenantContext.setOrgSlug(orgSlug);
        MDC.put("tenant", projectId);
        MDC.put("org", orEmpty(orgSlug));
        MDC.put("project_name", claims != null ? orEmpty(claims.projectName()) : "");
        MDC.put("org_name", claims != null ? orEmpty(claims.orgName()) : "");
        Span span = Span.current();
        span.setAttribute("tenant.id", projectId);
        span.setAttribute("org.slug", orEmpty(orgSlug));
        span.setAttribute("project.name", claims != null ? orEmpty(claims.projectName()) : "");
        span.setAttribute("org.name", claims != null ? orEmpty(claims.orgName()) : "");
    }

    private static void clearTenantContext(String projectId) {
        if (projectId == null) {
            return;
        }
        TenantContext.clear();
        MDC.remove("tenant");
        MDC.remove("org");
        MDC.remove("project_name");
        MDC.remove("org_name");
    }

    /**
     * Writes a GraphQL-shaped error carrying a stable machine-readable code in
     * {@code extensions.code}, matching how RLS denials are reported.
     */
    private static void writeError(HttpServletResponse response, int status, String message, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"errors\":[{\"message\":\"" + escape(message)
                + "\",\"extensions\":{\"code\":\"" + escape(code) + "\"}}]}");
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
