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

public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String JWT_CLAIMS_ATTR = SecurityConstants.JWT_CLAIMS_ATTR;

    private final JwtService jwtService;
    private final PostgresRoleResolver roleResolver;
    private final RlsPolicyEnforcer rlsEnforcer;

    public JwtAuthFilter(JwtService jwtService, PostgresRoleResolver roleResolver) {
        this(jwtService, roleResolver, null);
    }

    public JwtAuthFilter(JwtService jwtService, PostgresRoleResolver roleResolver,
                         RlsPolicyEnforcer rlsEnforcer) {
        this.jwtService = jwtService;
        this.roleResolver = roleResolver;
        this.rlsEnforcer = rlsEnforcer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        JwtClaims claims;
        try {
            claims = verifyClaims(request);
        } catch (JwtVerificationException _) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
            return;
        }

        // Resolve Postgres role once per request — single source of truth, exposed
        // to both GraphQL and REST controllers via RoleContext (starter ThreadLocal).
        // RoleNotAllowedException is converted to a 403 here so it surfaces uniformly
        // for filter-based REST traffic (which @RestControllerAdvice can't reach).
        String resolvedRole;
        try {
            resolvedRole = roleResolver != null ? roleResolver.resolve(claims) : null;
        } catch (RoleNotAllowedException ex) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, ex.getMessage());
            return;
        }
        if (resolvedRole != null) {
            RoleContext.setRole(resolvedRole);
        }

        try {
            applyTenantContext(claims);
            applyRlsContext(claims);
            chain.doFilter(request, response);
        } finally {
            RlsContext.clear();
            RoleContext.clear();
            clearTenantContext(claims);
        }
    }

    /**
     * Registers the query-first RLS contributor for this request. A no-op when
     * the engine isn't wired or the request carries no project context. Safe to
     * always call: with no policies for the project the contributor yields no
     * predicate, so existing deploys see zero behaviour change until a policy
     * is authored.
     */
    private void applyRlsContext(JwtClaims claims) {
        if (rlsEnforcer == null || claims == null || claims.projectId() == null) {
            return;
        }
        RlsContext.set(new EngineRlsWhereContributor(rlsEnforcer, claims.projectId(), claims));
        RlsContext.setColumnMask(new EngineColumnMaskContributor(rlsEnforcer, claims.projectId(), claims));
        RlsContext.setRowCheck(new EngineRowCheckContributor(rlsEnforcer, claims.projectId(), claims));
    }

    private JwtClaims verifyClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        JwtClaims claims = jwtService.verify(authHeader.substring(7));
        request.setAttribute(JWT_CLAIMS_ATTR, claims);
        return claims;
    }

    private static void applyTenantContext(JwtClaims claims) {
        if (claims == null || claims.projectId() == null) {
            return;
        }
        TenantContext.setTenantId(claims.projectId());
        TenantContext.setOrgSlug(claims.orgSlug());
        MDC.put("tenant", claims.projectId());
        MDC.put("org", claims.orgSlug());
        MDC.put("project_name", orEmpty(claims.projectName()));
        MDC.put("org_name", orEmpty(claims.orgName()));
        Span span = Span.current();
        span.setAttribute("tenant.id", claims.projectId());
        span.setAttribute("org.slug", claims.orgSlug());
        span.setAttribute("project.name", orEmpty(claims.projectName()));
        span.setAttribute("org.name", orEmpty(claims.orgName()));
    }

    private static void clearTenantContext(JwtClaims claims) {
        if (claims == null || claims.projectId() == null) {
            return;
        }
        TenantContext.clear();
        MDC.remove("tenant");
        MDC.remove("org");
        MDC.remove("project_name");
        MDC.remove("org_name");
    }

    private static void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"errors\":[{\"message\":\"" + escape(message) + "\"}]}");
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
