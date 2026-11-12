package io.github.excalibase.security;

import io.github.excalibase.access.AccessPlan;
import io.github.excalibase.access.RequestGuard;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.schema.SchemaUnavailableException;
import io.github.excalibase.service.VaultCredentialException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.opentelemetry.api.trace.Span;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Verifies the request's token, resolves who it runs as, and hands the project, role and permission guards to the
 * handler. The project comes from the URL path only, already matched to a project this deployment serves.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
@Requires(property = "app.security.jwt-enabled", notEquals = "false")
public class JwtAuthFilter implements Ordered {

    public static final String JWT_CLAIMS_ATTR = SecurityConstants.JWT_CLAIMS_ATTR;
    public static final int ORDER = -80;

    /** Code reported when the URL project and the token project disagree. */
    private static final String PROJECT_MISMATCH_CODE = "project_mismatch";

    private final JwtService jwtService;
    private final AccessPlans accessPlans;

    public JwtAuthFilter(JwtService jwtService, @Nullable AccessPlans accessPlans) {
        this.jwtService = jwtService;
        this.accessPlans = accessPlans;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request, MutablePropagatedContext propagated) {
        String pathProjectId = ProjectPath.fromUri(request.getPath());

        JwtClaims claims;
        try {
            claims = verifyClaims(request, pathProjectId);
        } catch (JwtVerificationException e) {
            return FilterErrors.write(HttpStatus.UNAUTHORIZED, "Invalid or expired token", e.code());
        }

        if (pathProjectId != null && claims != null && claims.projectId() != null
                && !pathProjectId.equals(claims.projectId())) {
            return FilterErrors.write(HttpStatus.FORBIDDEN, "URL project does not match token project",
                    PROJECT_MISMATCH_CODE);
        }
        Principal principal;
        try {
            principal = RoleResolver.resolve(claims, request.getHeaders().get(RoleResolver.ROLE_HEADER),
                    excalibaseHeaders(request));
        } catch (RoleNotAllowedException e) {
            return FilterErrors.write(HttpStatus.FORBIDDEN, "Role not allowed", e.code());
        }
        request.getAttributes().put(SecurityConstants.PRINCIPAL_ATTR, principal);
        if (pathProjectId == null) {
            return null;
        }
        String orgSlug = claims != null ? claims.orgSlug() : null;
        TenantScope scope = new TenantScope(pathProjectId, orgSlug);
        scope.enter();
        try {
            return applyGuards(pathProjectId, principal, claims, propagated);
        } finally {
            scope.leave();
            if (accessPlans != null) {
                accessPlans.requestFinished();
            }
        }
    }

    /** The tenant on this thread while the access plan is read (it routes the plan's database reads). */
    private record TenantScope(String projectId, String orgSlug) {
        void enter() {
            io.github.excalibase.config.datasource.TenantContext.setTenantId(projectId);
            io.github.excalibase.config.datasource.TenantContext.setOrgSlug(orgSlug);
        }

        void leave() {
            io.github.excalibase.config.datasource.TenantContext.clear();
        }
    }

    /**
     * Registers this request's guards, made from the role's access plan and the request's session variables.
     * Answers the request itself when the plan cannot be had: a project whose permissions cannot be read is
     * refused, never served without them.
     */
    private HttpResponse<?> applyGuards(String projectId, Principal principal, JwtClaims claims,
                                        MutablePropagatedContext propagated) {
        AccessPlan plan = null;
        if (accessPlans != null) {
            try {
                plan = accessPlans.planFor(principal);
            } catch (PermissionsUnavailableException e) {
                return FilterErrors.write(HttpStatus.SERVICE_UNAVAILABLE, "Permissions unavailable",
                        PermissionsUnavailableException.CODE);
            } catch (UnknownProjectException e) {
                return FilterErrors.write(HttpStatus.NOT_FOUND, "Project not found", "project_not_found");
            } catch (VaultCredentialException | SchemaUnavailableException e) {
                return FilterErrors.write(HttpStatus.SERVICE_UNAVAILABLE, "Project unavailable",
                        "project_unavailable");
            }
        }
        Map<String, String> sessionVariables = accessPlans == null ? null : principal.sessionVariables(projectId);
        RequestGuard guard = null;
        if (accessPlans != null && plan != null && !plan.allAccess()) {
            guard = plan.guard(sessionVariables);
        }
        String projectName = claims != null ? claims.projectName() : null;
        String orgName = claims != null ? claims.orgName() : null;
        Span span = Span.current();
        span.setAttribute("tenant.id", projectId);
        span.setAttribute("org.slug", claims != null && claims.orgSlug() != null ? claims.orgSlug() : "");
        span.setAttribute("project.name", projectName != null ? projectName : "");
        span.setAttribute("org.name", orgName != null ? orgName : "");
        propagated.add(new RequestContextElement(projectId, claims != null ? claims.orgSlug() : null,
                projectName, orgName, guard, guard, sessionVariables));
        return null;
    }

    /** The request's {@code x-excalibase-*} headers, which carry session variables for a service token. */
    private static Map<String, String> excalibaseHeaders(HttpRequest<?> request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : request.getHeaders().names()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(Principal.SESSION_VARIABLE_PREFIX)) {
                headers.put(name, request.getHeaders().get(name));
            }
        }
        return headers;
    }

    private JwtClaims verifyClaims(HttpRequest<?> request, String pathProjectId) {
        String authHeader = request.getHeaders().get("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        // EXC-11: the path project is what the audience must cover.
        JwtClaims claims = jwtService.verify(authHeader.substring(7), pathProjectId);
        request.getAttributes().put(JWT_CLAIMS_ATTR, claims);
        return claims;
    }
}
