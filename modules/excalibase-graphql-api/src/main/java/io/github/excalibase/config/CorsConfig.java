package io.github.excalibase.config;

import io.github.excalibase.cors.ProjectCorsConfigurationSource;
import io.github.excalibase.cors.ProjectCorsProvider;
import io.github.excalibase.cors.ProvisioningProjectCorsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for the public, browser-facing API (the SDK runs in browser JS).
 *
 * <p>Project routes ({@code /{projectId}/graphql}, {@code /{projectId}/api/v1/...},
 * and the WebSocket upgrades on them) are answered from the project's own
 * allowlist, fetched from provisioning ({@code app.cors.provisioning-url},
 * defaulting to the RLS policy URL) and cached for {@code app.cors.ttl-ms}.
 * Routes without a project (health, actuator, the legacy unscoped paths) use
 * {@code app.cors.allowed-origins}. Without a provisioning URL — standalone
 * mode — that platform default applies everywhere, as before.
 *
 * <p>The choice is made at runtime rather than via {@code @ConditionalOnProperty}
 * so it survives GraalVM AOT. The source is installed on the Spring Security
 * chain (see {@link SecurityConfig}) so it runs before auth and covers every
 * servlet path, not only MVC handler mappings.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:*}") String platformOrigins,
            @Value("${app.cors.provisioning-url:${app.security.rls.policy-url:}}") String provisioningUrl,
            @Value("${app.cors.provisioning-pat:${app.security.rls.policy-pat:${app.security.multi-tenant.provisioning-pat:}}}") String provisioningPat,
            @Value("${app.cors.ttl-ms:30000}") long ttlMillis) {
        ProjectCorsProvider provider = null;
        if (provisioningUrl != null && !provisioningUrl.isBlank()) {
            provider = new ProvisioningProjectCorsProvider(provisioningUrl, provisioningPat, ttlMillis);
        }
        return new ProjectCorsConfigurationSource(provider, ProjectCorsConfigurationSource.configFor(split(platformOrigins)));
    }

    private static List<String> split(String origins) {
        return Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
