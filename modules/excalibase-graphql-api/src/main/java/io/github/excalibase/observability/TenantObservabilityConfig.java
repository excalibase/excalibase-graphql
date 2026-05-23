package io.github.excalibase.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TenantObservationConvention} into Spring Boot's web observation
 * pipeline. Spring Boot auto-detects a bean of type
 * {@code ServerRequestObservationConvention} and uses it as the default for
 * {@code http.server.requests}, so the tenant/org labels appear on all HTTP
 * request metrics + traces without any additional configuration.
 */
@Configuration
public class TenantObservabilityConfig {

    @Bean
    public TenantObservationConvention tenantObservationConvention() {
        return new TenantObservationConvention();
    }
}
