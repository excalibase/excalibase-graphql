package io.github.excalibase.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Authentication is on unless an operator explicitly opts into insecure dev
 * mode; with it off the process refuses to start rather than serve every row.
 */
class InsecureDevModeGuardTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(InsecureDevModeGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

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
    @DisplayName("authentication off without the insecure dev flag refuses to start")
    void authOffWithoutFlag_refuses() {
        InsecureDevModeGuard guard = new InsecureDevModeGuard(false, "");

        assertThatThrownBy(guard::check)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.insecure-dev-mode");
    }

    @Test
    @DisplayName("authentication off with the insecure dev flag starts and logs at ERROR")
    void authOffWithFlag_logsLoudly() {
        new InsecureDevModeGuard(true, "").check();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("INSECURE DEV MODE");
    }

    @Test
    @DisplayName("insecure dev mode with RLS policies configured names the unenforced policy source")
    void authOffWithPolicies_namesThePolicySource() {
        new InsecureDevModeGuard(true, "http://provisioning/api").check();

        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("Row-Level Security is NOT enforced")
                .contains("http://provisioning/api");
    }

    @Test
    @DisplayName("with no property set authentication is on and the guard is not involved")
    void noProperty_guardInactive() {
        try (ApplicationContext context = ApplicationContext.run(
                engine(Map.of("app.security.auth.hmac-secret", "test-hmac-secret-0123456789abcdef0123456789")))) {
            assertThat(context.containsBean(InsecureDevModeGuard.class)).isFalse();
        }
    }

    @Test
    @DisplayName("jwt-enabled=false alone fails the context")
    void authOffProperty_failsContext() {
        assertThatThrownBy(() -> ApplicationContext.run(engine(Map.of("app.security.jwt-enabled", "false"))).close())
                .hasStackTraceContaining("app.security.insecure-dev-mode");
    }

    @Test
    @DisplayName("jwt-enabled=false with the insecure dev flag starts")
    void authOffPropertyWithFlag_starts() {
        try (ApplicationContext context = ApplicationContext.run(engine(Map.of(
                "app.security.jwt-enabled", "false", "app.security.insecure-dev-mode", "true")))) {
            assertThat(context.getBeansOfType(InsecureDevModeGuard.class)).hasSize(1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "jwt-enabled={0} is authentication off, so the guard refuses it")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FALSE", "False", "fAlSe"})
    @DisplayName("any case of false reaches the guard")
    void falseInAnyCase_failsContext(String value) {
        assertThatThrownBy(() -> ApplicationContext.run(engine(Map.of("app.security.jwt-enabled", value))).close())
                .hasStackTraceContaining("app.security.insecure-dev-mode");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "jwt-enabled={0} is refused")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "no", "off", "yes", "disabled", "1", "on"})
    @DisplayName("a value that is not true or false stops start-up instead of being read as one of them")
    void notABoolean_failsContext(String value) {
        assertThatThrownBy(() -> ApplicationContext.run(engine(Map.of("app.security.jwt-enabled", value,
                "app.security.insecure-dev-mode", "true",
                "app.security.auth.hmac-secret", "test-hmac-secret-0123456789abcdef0123456789"))).close())
                .hasStackTraceContaining("app.security.jwt-enabled must be true or false");
    }

    @Test
    @DisplayName("FALSE with the insecure dev flag starts with no auth filter")
    void upperCaseFalseWithFlag_hasNoAuthFilter() {
        try (ApplicationContext context = ApplicationContext.run(engine(Map.of(
                "app.security.jwt-enabled", "FALSE", "app.security.insecure-dev-mode", "true")))) {
            assertThat(context.getBeansOfType(InsecureDevModeGuard.class)).hasSize(1);
            assertThat(context.containsBean(io.github.excalibase.security.JwtAuthFilter.class)).isFalse();
        }
    }

    @Test
    @DisplayName("TRUE keeps the auth filter and no guard")
    void upperCaseTrue_keepsAuthFilter() {
        try (ApplicationContext context = ApplicationContext.run(engine(Map.of(
                "app.security.jwt-enabled", "TRUE",
                "app.security.auth.hmac-secret", "test-hmac-secret-0123456789abcdef0123456789")))) {
            assertThat(context.containsBean(InsecureDevModeGuard.class)).isFalse();
            assertThat(context.containsBean(io.github.excalibase.security.JwtAuthFilter.class)).isTrue();
        }
    }

    @Test
    @DisplayName("a metadata source set while authentication is off is warned about, like a policy URL")
    void metadataSourceWithAuthOff_isWarnedAbout() {
        new InsecureDevModeGuard(true, "", "file").check();

        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("metadata.source=file")
                && event.getFormattedMessage().contains("NOT enforced"));
    }
}
