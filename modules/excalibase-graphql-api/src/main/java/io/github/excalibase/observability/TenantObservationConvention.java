package io.github.excalibase.observability;

import io.github.excalibase.config.datasource.TenantContext;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.SecurityConstants;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds {@code tenant.id}, {@code org.slug}, {@code project.name}, and
 * {@code org.name} low-cardinality tags to the {@code http.server.requests}
 * Micrometer timer. These tags are exposed on {@code /actuator/prometheus}
 * and emitted via OTLP as span/metric attributes, letting Grafana/Prometheus
 * dashboards filter by either opaque ref ({@code tenant.id}) or human-
 * readable display name ({@code project.name} / {@code org.name}) so support
 * engineers can find tenants by what users call them.
 *
 * <p>The observation lifecycle completes <em>after</em> {@code JwtAuthFilter}'s
 * {@code finally} block has cleared {@link TenantContext}, so a pure
 * ThreadLocal read returns {@code null} here. We fall back to the
 * {@code HttpServletRequest} attribute (set by {@code JwtAuthFilter} before
 * {@code chain.doFilter}) which remains readable through the entire request
 * scope including the observation stop phase.
 *
 * <p>Falls back to {@code "none"} when no tenant context is active — e.g.
 * health checks, unauthenticated probes, or the no-JWT request path.
 */
public class TenantObservationConvention extends DefaultServerRequestObservationConvention {

    private static final String TENANT_ID_KEY = "tenant.id";
    private static final String ORG_SLUG_KEY = "org.slug";
    private static final String PROJECT_NAME_KEY = "project.name";
    private static final String ORG_NAME_KEY = "org.name";
    private static final String NONE = "none";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        String tenantId = TenantContext.getTenantId();
        String orgSlug = TenantContext.getOrgSlug();
        String projectName = null;
        String orgName = null;
        JwtClaims claims = claimsFrom(context);
        if (claims != null) {
            if (tenantId == null) {
                tenantId = claims.projectId();
                orgSlug = claims.orgSlug();
            }
            projectName = claims.projectName();
            orgName = claims.orgName();
        }
        return super.getLowCardinalityKeyValues(context)
                .and(KeyValue.of(TENANT_ID_KEY, orNone(tenantId)))
                .and(KeyValue.of(ORG_SLUG_KEY, orNone(orgSlug)))
                .and(KeyValue.of(PROJECT_NAME_KEY, orNone(projectName)))
                .and(KeyValue.of(ORG_NAME_KEY, orNone(orgName)));
    }

    private static JwtClaims claimsFrom(ServerRequestObservationContext context) {
        HttpServletRequest req = context.getCarrier();
        if (req == null) {
            return null;
        }
        Object attr = req.getAttribute(SecurityConstants.JWT_CLAIMS_ATTR);
        return attr instanceof JwtClaims claims ? claims : null;
    }

    private static String orNone(String value) {
        return value != null && !value.isEmpty() ? value : NONE;
    }
}
