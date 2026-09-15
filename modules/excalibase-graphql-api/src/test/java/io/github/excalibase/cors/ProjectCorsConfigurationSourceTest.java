package io.github.excalibase.cors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProjectCorsConfigurationSource}: the project comes from
 * the URL path (same rule as RLS), its origins come from the provider, and a
 * provider failure denies rather than falling back to the platform default.
 */
class ProjectCorsConfigurationSourceTest {

    private static final String PROJECT = "proj-a";
    private static final String ORIGIN_A = "https://app.example.com";
    private static final String ORIGIN_EVIL = "https://evil.example";
    private static final String GLOBAL_ORIGIN = "https://studio.example.com";

    private static final CorsConfiguration GLOBAL = ProjectCorsConfigurationSource.configFor(List.of(GLOBAL_ORIGIN));

    private static MockHttpServletRequest request(String uri, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }

    private static ProjectCorsProvider providerWith(Map<String, List<String>> origins) {
        return projectId -> {
            List<String> list = origins.get(projectId);
            if (list == null) throw new CorsOriginsFetchException("no such project: " + projectId);
            return list;
        };
    }

    @Test
    @DisplayName("a project route resolves the project's own allowlist")
    void projectRouteUsesProjectOrigins() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                providerWith(Map.of(PROJECT, List.of(ORIGIN_A))), GLOBAL);

        CorsConfiguration config = source.getCorsConfiguration(request("/" + PROJECT + "/graphql", ORIGIN_A));

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin(ORIGIN_A)).isEqualTo(ORIGIN_A);
        assertThat(config.checkOrigin(ORIGIN_EVIL)).isNull();
        assertThat(config.checkOrigin(GLOBAL_ORIGIN)).as("platform default must not leak into a project").isNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("REST and realtime paths resolve the same project")
    void restPathUsesProjectOrigins() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                providerWith(Map.of(PROJECT, List.of(ORIGIN_A))), GLOBAL);

        assertThat(source.getCorsConfiguration(request("/" + PROJECT + "/api/v1/customer", ORIGIN_A)).checkOrigin(ORIGIN_A))
                .isEqualTo(ORIGIN_A);
        assertThat(source.getCorsConfiguration(request("/" + PROJECT + "/api/v1/realtime", ORIGIN_A)).checkOrigin(ORIGIN_A))
                .isEqualTo(ORIGIN_A);
    }

    @Test
    @DisplayName("a project with no origins yields a configuration that allows nothing")
    void emptyAllowlistDeniesEveryOrigin() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                providerWith(Map.of(PROJECT, List.of())), GLOBAL);

        CorsConfiguration config = source.getCorsConfiguration(request("/" + PROJECT + "/graphql", ORIGIN_A));

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin(ORIGIN_A)).isNull();
    }

    @Test
    @DisplayName("the wildcard entry allows any origin without credentials")
    void wildcardAllowsAnyOrigin() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                providerWith(Map.of(PROJECT, List.of("*"))), GLOBAL);

        CorsConfiguration config = source.getCorsConfiguration(request("/" + PROJECT + "/graphql", ORIGIN_EVIL));

        assertThat(config.checkOrigin(ORIGIN_EVIL)).isEqualTo("*");
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    @DisplayName("fail-closed: a provider failure denies instead of using the platform default")
    void providerFailureDenies() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(
                providerWith(Map.of()), GLOBAL);

        CorsConfiguration config = source.getCorsConfiguration(request("/unknown/graphql", GLOBAL_ORIGIN));

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin(GLOBAL_ORIGIN)).isNull();
        assertThat(config.checkOrigin(ORIGIN_A)).isNull();
    }

    @Test
    @DisplayName("non-project routes keep the platform default")
    void nonProjectRouteUsesGlobal() {
        AtomicInteger providerCalls = new AtomicInteger();
        ProjectCorsProvider provider = projectId -> {
            providerCalls.incrementAndGet();
            return List.of(ORIGIN_A);
        };
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(provider, GLOBAL);

        assertThat(source.getCorsConfiguration(request("/actuator/health", GLOBAL_ORIGIN)).checkOrigin(GLOBAL_ORIGIN))
                .isEqualTo(GLOBAL_ORIGIN);
        assertThat(source.getCorsConfiguration(request("/graphql", GLOBAL_ORIGIN)).checkOrigin(GLOBAL_ORIGIN))
                .isEqualTo(GLOBAL_ORIGIN);
        assertThat(providerCalls.get()).as("no provider lookup without a project").isZero();
    }

    @Test
    @DisplayName("without a provider (no provisioning configured) every route keeps the platform default")
    void noProviderUsesGlobalEverywhere() {
        ProjectCorsConfigurationSource source = new ProjectCorsConfigurationSource(null, GLOBAL);

        assertThat(source.getCorsConfiguration(request("/" + PROJECT + "/graphql", GLOBAL_ORIGIN)).checkOrigin(GLOBAL_ORIGIN))
                .isEqualTo(GLOBAL_ORIGIN);
    }

    @Test
    @DisplayName("the shared configuration carries the browser API's methods and exposed headers")
    void configForCarriesSharedSettings() {
        CorsConfiguration config = ProjectCorsConfigurationSource.configFor(List.of(ORIGIN_A));

        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(config.getExposedHeaders()).contains("Content-Range", "Location");
        assertThat(config.getAllowedHeaders()).contains("*");
        assertThat(config.getMaxAge()).isEqualTo(3600L);
    }
}
