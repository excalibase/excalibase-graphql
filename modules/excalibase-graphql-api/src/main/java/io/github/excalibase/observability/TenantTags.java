package io.github.excalibase.observability;

import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The tenant, org and project labels of a request, shared by the observation convention and the
 * request timer filter so both name a tenant the same way.
 *
 * <p>{@link TenantContext} is cleared by {@code JwtAuthFilter} before either reads it at the end of
 * a request, so the claims {@code JwtAuthFilter} left on the request are the fallback. Anything
 * missing is {@code "none"}.
 */
record TenantTags(String tenantId, String orgSlug, String projectName, String orgName) {

    private static final String NONE = "none";

    static TenantTags of(HttpServletRequest request) {
        String tenantId = TenantContext.getTenantId();
        String orgSlug = TenantContext.getOrgSlug();
        String projectName = null;
        String orgName = null;
        JwtClaims claims = request == null ? null : claimsFrom(request);
        if (claims != null) {
            if (tenantId == null) {
                tenantId = claims.projectId();
                orgSlug = claims.orgSlug();
            }
            projectName = claims.projectName();
            orgName = claims.orgName();
        }
        return new TenantTags(orNone(tenantId), orNone(orgSlug), orNone(projectName), orNone(orgName));
    }

    private static JwtClaims claimsFrom(HttpServletRequest request) {
        Object attr = request.getAttribute(SecurityConstants.JWT_CLAIMS_ATTR);
        return attr instanceof JwtClaims claims ? claims : null;
    }

    private static String orNone(String value) {
        return value != null && !value.isEmpty() ? value : NONE;
    }
}
