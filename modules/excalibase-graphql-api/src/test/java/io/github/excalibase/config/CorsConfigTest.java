package io.github.excalibase.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the CORS default is deny-by-default: no allowed-origins value means
 * no cross-origin mapping is registered, while an explicit value is honoured.
 */
class CorsConfigTest {

    /** Exposes the otherwise-protected registered CORS configurations. */
    private static final class ProbeRegistry extends CorsRegistry {
        @Override
        protected Map<String, CorsConfiguration> getCorsConfigurations() {
            return super.getCorsConfigurations();
        }
    }

    private Map<String, CorsConfiguration> configsFor(String allowedOrigins) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", allowedOrigins);
        ProbeRegistry registry = new ProbeRegistry();
        config.addCorsMappings(registry);
        return registry.getCorsConfigurations();
    }

    @Test
    @DisplayName("empty allowed-origins → no cross-origin mapping registered (deny by default)")
    void emptyOrigins_noMapping() {
        assertThat(configsFor("")).doesNotContainKey("/graphql");
    }

    @Test
    @DisplayName("blank/whitespace allowed-origins → no cross-origin mapping")
    void blankOrigins_noMapping() {
        assertThat(configsFor("   ")).doesNotContainKey("/graphql");
    }

    @Test
    @DisplayName("explicit origin → mapping registered with that origin only")
    void explicitOrigin_mappingRegistered() {
        Map<String, CorsConfiguration> configs = configsFor("https://app.example.com");
        assertThat(configs).containsKey("/graphql");
        assertThat(configs.get("/graphql").getAllowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @Test
    @DisplayName("multiple comma-separated origins → all registered, blanks dropped")
    void multipleOrigins_allRegistered() {
        Map<String, CorsConfiguration> configs =
                configsFor("https://a.example.com, https://b.example.com, ");
        assertThat(configs.get("/graphql").getAllowedOrigins())
                .containsExactly("https://a.example.com", "https://b.example.com");
    }
}
