package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.micronaut.context.ApplicationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JwtSecurityConfigTest {

    private static final String SECRET = "test-hmac-secret-0123456789abcdef0123456789";

    private static Map<String, Object> engine(Map<String, Object> extra) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.nats.enabled", "false",
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1"));
        properties.putAll(extra);
        return properties;
    }

    @Test
    @DisplayName("with no property set authentication is on, so a missing verifier fails startup")
    void noProperty_authOnByDefault() {
        assertThatThrownBy(() -> ApplicationContext.run(engine(Map.of())).close())
                .hasStackTraceContaining("app.security.auth.jwks-url");
    }

    @Test
    @DisplayName("authentication on with neither JWKS nor HMAC refuses with a clear message")
    void noVerifier_refuses() {
        SecurityProperties props = new SecurityProperties(true,
                new SecurityProperties.Auth(null, " ", null, null, null, null), null);

        assertThatThrownBy(() -> new AppConfig().jwtService(props, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.auth.jwks-url")
                .hasMessageContaining("app.security.auth.hmac-secret")
                .hasMessageContaining("app.security.insecure-dev-mode");
    }

    @Test
    @DisplayName("both a JWKS URL and an HMAC secret is refused, never one picked silently")
    void bothVerifiers_refused() {
        SecurityProperties props = new SecurityProperties(true,
                new SecurityProperties.Auth("http://auth/jwks.json", SECRET, null, null, null, null), null);

        assertThatThrownBy(() -> new AppConfig().jwtService(props, 30))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("tenant routing exists only when a provisioning URL is actually set, not for a blank one")
    void tenantRouting_onlyWithAProvisioningUrl() {
        try (ApplicationContext blank = ApplicationContext.run(engine(Map.of(
                "app.security.auth.hmac-secret", SECRET, "app.security.multi-tenant.provisioning-url", "")))) {
            assertThat(blank.containsBean(DynamicDataSourceManager.class)).isFalse();
        }
        Map<String, Object> multiTenant = engine(Map.of(
                "app.security.auth.hmac-secret", SECRET,
                "app.security.multi-tenant.provisioning-url", "http://provisioning/api"));
        multiTenant.keySet().removeIf(key -> key.startsWith("spring.datasource."));   // every project has its own database
        try (ApplicationContext configured = ApplicationContext.run(multiTenant)) {
            assertThat(configured.containsBean(DynamicDataSourceManager.class)).isTrue();
        }
    }

    @Test
    @DisplayName("closing the context destroys the tenant pools, a factory-built bean gets no @PreDestroy")
    void tenantRouting_closedWithTheContext() {
        Map<String, Object> multiTenant = engine(Map.of(
                "app.security.auth.hmac-secret", SECRET,
                "app.security.multi-tenant.provisioning-url", "http://provisioning/api"));
        multiTenant.keySet().removeIf(key -> key.startsWith("spring.datasource."));
        Logger managerLog = (Logger) LoggerFactory.getLogger(DynamicDataSourceManager.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        managerLog.addAppender(logs);
        try {
            ApplicationContext.run(multiTenant).close();
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .contains("tenant_datasource_manager_destroyed");
        } finally {
            managerLog.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("a tenant sslmode other than verify-full or disable fails startup, in any mode")
    void tenantSslMode_weakerModesRefused() {
        assertThatThrownBy(() -> ApplicationContext.run(engine(Map.of(
                "app.security.auth.hmac-secret", SECRET, "app.tenant-db.sslmode", "require"))).close())
                .hasStackTraceContaining("TENANT_DB_SSLMODE");
    }
}
