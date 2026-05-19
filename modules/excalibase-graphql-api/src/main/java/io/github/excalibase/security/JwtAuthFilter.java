package io.github.excalibase.security;

import io.github.excalibase.config.datasource.TenantContext;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String JWT_CLAIMS_ATTR = SecurityConstants.JWT_CLAIMS_ATTR;

    private final JwtService jwtService;
    private final PostgresRoleResolver roleResolver;

    public JwtAuthFilter(JwtService jwtService, PostgresRoleResolver roleResolver) {
        this.jwtService = jwtService;
        this.roleResolver = roleResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        JwtClaims claims = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                claims = jwtService.verify(token);
                request.setAttribute(JWT_CLAIMS_ATTR, claims);
            } catch (JwtVerificationException _) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"errors\":[{\"message\":\"Invalid or expired token\"}]}");
                return;
            }
        }

        // Resolve Postgres role once per request — single source of truth, exposed
        // to both GraphQL and REST controllers via RoleContext (starter ThreadLocal).
        // RoleNotAllowedException is converted to a 403 here so it surfaces uniformly
        // for filter-based REST traffic (which @RestControllerAdvice can't reach).
        String resolvedRole;
        try {
            resolvedRole = roleResolver != null ? roleResolver.resolve(claims) : null;
        } catch (RoleNotAllowedException ex) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"errors\":[{\"message\":\"" + escape(ex.getMessage()) + "\"}]}");
            return;
        }
        if (resolvedRole != null) {
            RoleContext.setRole(resolvedRole);
        }

        try {
            if (claims != null && claims.projectId() != null) {
                TenantContext.setTenantId(claims.projectId());
                TenantContext.setOrgSlug(claims.orgSlug());
                MDC.put("tenant", claims.projectId());
                MDC.put("org", claims.orgSlug());
                MDC.put("project_name", claims.projectName() != null ? claims.projectName() : "");
                MDC.put("org_name", claims.orgName() != null ? claims.orgName() : "");
                Span.current().setAttribute("tenant.id", claims.projectId());
                Span.current().setAttribute("org.slug", claims.orgSlug());
                Span.current().setAttribute("project.name", claims.projectName() != null ? claims.projectName() : "");
                Span.current().setAttribute("org.name", claims.orgName() != null ? claims.orgName() : "");
            }
            chain.doFilter(request, response);
        } finally {
            RoleContext.clear();
            if (claims != null && claims.projectId() != null) {
                TenantContext.clear();
                MDC.remove("tenant");
                MDC.remove("org");
                MDC.remove("project_name");
                MDC.remove("org_name");
            }
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
