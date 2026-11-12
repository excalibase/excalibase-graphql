package io.github.excalibase.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
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
 * {@code finally} block has cleared {@code TenantContext}, so a pure
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

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        TenantTags tags = TenantTags.of(context.getCarrier());
        return super.getLowCardinalityKeyValues(context)
                .and(KeyValue.of(TENANT_ID_KEY, tags.tenantId()))
                .and(KeyValue.of(ORG_SLUG_KEY, tags.orgSlug()))
                .and(KeyValue.of(PROJECT_NAME_KEY, tags.projectName()))
                .and(KeyValue.of(ORG_NAME_KEY, tags.orgName()));
    }
}
