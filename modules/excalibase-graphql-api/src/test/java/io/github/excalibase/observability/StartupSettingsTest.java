package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StartupSettingsTest {

    private static final String ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";

    @Test
    @DisplayName("without a collector nothing is exported: no OTLP metrics registry")
    void noCollector() {
        assertThat(StartupSettings.from(Map.of()).get("micronaut.metrics.export.otlp.enabled")).isEqualTo("false");
        assertThat(StartupSettings.from(Map.of(ENDPOINT, "  ")).get("micronaut.metrics.export.otlp.enabled")).isEqualTo("false");
        assertThat(StartupSettings.from(Map.of())).doesNotContainKey("micronaut.metrics.export.otlp.url");
    }

    @Test
    @DisplayName("a collector gets metrics at its /v1/metrics path, a trailing slash ignored")
    void collector() {
        Map<String, Object> settings = StartupSettings.from(Map.of(ENDPOINT, "http://collector:4318/"));

        assertThat(settings).containsEntry("micronaut.metrics.export.otlp.enabled", "true")
                .containsEntry("micronaut.metrics.export.otlp.url", "http://collector:4318/v1/metrics");
    }

    @Test
    @DisplayName("a metrics endpoint of its own wins over the collector's")
    void ownMetricsEndpoint() {
        Map<String, Object> settings = StartupSettings.from(Map.of(ENDPOINT, "http://collector:4318",
                "MANAGEMENT_OTLP_METRICS_EXPORT_URL", "http://mimir:4318/v1/metrics"));

        assertThat(settings).containsEntry("micronaut.metrics.export.otlp.url", "http://mimir:4318/v1/metrics");
    }

    @Test
    @DisplayName("a metrics endpoint alone exports metrics only")
    void ownMetricsEndpointAlone() {
        Map<String, Object> settings = StartupSettings.from(Map.of("MANAGEMENT_OTLP_METRICS_EXPORT_URL", "http://mimir:4318/v1/metrics"));

        assertThat(settings).containsEntry("micronaut.metrics.export.otlp.enabled", "true");
    }

    @Test
    @DisplayName("LOGGING_LEVEL_* variables set logger levels, underscores standing for dots")
    void loggingLevels() {
        Map<String, Object> settings = StartupSettings.from(Map.of(
                "LOGGING_LEVEL_ROOT", "debug",
                "LOGGING_LEVEL_IO_GITHUB_EXCALIBASE", "TRACE",
                "LOGGING_LEVEL_ORG_SPRINGFRAMEWORK", "warn"));

        assertThat(settings).containsEntry("logger.levels.ROOT", "DEBUG")
                .containsEntry("logger.levels.io.github.excalibase", "TRACE")
                .containsEntry("logger.levels.org.springframework", "WARN");
    }

    @Test
    @DisplayName("LOG_LEVEL_ROOT, _APP and _SPRING keep working as the root, engine and Spring levels")
    void legacyLevels() {
        Map<String, Object> settings = StartupSettings.from(Map.of(
                "LOG_LEVEL_ROOT", "warn", "LOG_LEVEL_APP", "debug", "LOG_LEVEL_SPRING", "error"));

        assertThat(settings).containsEntry("logger.levels.ROOT", "WARN")
                .containsEntry("logger.levels.io.github.excalibase", "DEBUG")
                .containsEntry("logger.levels.org.springframework", "ERROR");
    }

    @Test
    @DisplayName("a level that is not a level stops start-up instead of being guessed")
    void invalidLevel() {
        assertThatThrownBy(() -> StartupSettings.from(Map.of("LOGGING_LEVEL_ROOT", "loud")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOGGING_LEVEL_ROOT");
    }

    @Test
    @DisplayName("LOG_FORMAT=plain is refused at start, as the earlier build refused it; ecs is the one format")
    void logFormat() {
        assertThatThrownBy(() -> StartupSettings.from(Map.of("LOG_FORMAT", "plain")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown format 'plain'");
        assertThat(StartupSettings.from(Map.of("LOG_FORMAT", "ecs"))).isNotNull();
    }

    @Test
    @DisplayName("logger levels are applied to the logging system before anything starts, so no early line escapes them")
    void levelsAreAppliedAtOnce() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.github.excalibase.levelcheck");
        ch.qos.logback.classic.Level before = logger.getLevel();
        try {
            StartupSettings.applyLoggerLevels(StartupSettings.from(Map.of("LOGGING_LEVEL_IO_GITHUB_EXCALIBASE_LEVELCHECK", "error")));

            assertThat(logger.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
        } finally {
            logger.setLevel(before);
        }
    }

    @Test
    @DisplayName("the histogram variable the platform chart sets becomes the property the timer reads")
    void histogramVariable() {
        assertThat(StartupSettings.from(Map.of("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS", "true")))
                .containsEntry("management.metrics.distribution.percentiles-histogram.http.server.requests", "true");
        assertThat(StartupSettings.from(Map.of())).doesNotContainKey("management.metrics.distribution.percentiles-histogram.http.server.requests");
        assertThatThrownBy(() -> StartupSettings.from(Map.of("MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS", "yes")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("on Kubernetes (host and port variables) the probe groups are on, as in the earlier build")
    void probesOnKubernetes() {
        Map<String, Object> settings = StartupSettings.from(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1", "KUBERNETES_SERVICE_PORT", "443"));

        assertThat(settings).containsEntry("management.endpoint.health.probes.enabled", "true");
    }

    @Test
    @DisplayName("one Kubernetes variable alone, or an explicit probes setting, leaves the probes to their own setting")
    void probesNotImplied() {
        assertThat(StartupSettings.from(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"))).doesNotContainKey("management.endpoint.health.probes.enabled");
        assertThat(StartupSettings.from(Map.of())).doesNotContainKey("management.endpoint.health.probes.enabled");
        assertThat(StartupSettings.from(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1", "KUBERNETES_SERVICE_PORT", "443",
                "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", "false"))).doesNotContainKey("management.endpoint.health.probes.enabled");
    }

    @Test
    @DisplayName("SERVER_SHUTDOWN=immediate switches the drain off; graceful and unset leave it on")
    void shutdownMode() {
        assertThat(StartupSettings.from(Map.of("SERVER_SHUTDOWN", "immediate")))
                .containsEntry("micronaut.lifecycle.graceful-shutdown.enabled", "false");
        assertThat(StartupSettings.from(Map.of("SERVER_SHUTDOWN", "Graceful"))).doesNotContainKey("micronaut.lifecycle.graceful-shutdown.enabled");
        assertThat(StartupSettings.from(Map.of())).doesNotContainKey("micronaut.lifecycle.graceful-shutdown.enabled");
        assertThatThrownBy(() -> StartupSettings.from(Map.of("SERVER_SHUTDOWN", "soon")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SERVER_SHUTDOWN");
    }

    @Test
    @DisplayName("the drain timeout takes the durations Spring does: 30s, 1m, 500ms, ISO-8601, a bare number of milliseconds")
    void shutdownTimeout() {
        assertThat(grace("30s")).isEqualTo("PT30S");
        assertThat(grace("1m")).isEqualTo("PT1M");
        assertThat(grace("500ms")).isEqualTo("PT0.5S");
        assertThat(grace("PT45S")).isEqualTo("PT45S");
        assertThat(grace("2000")).isEqualTo("PT2S");
        assertThatThrownBy(() -> grace("soon")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE");
    }

    private static Object grace(String value) {
        return StartupSettings.from(Map.of("SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE", value))
                .get("micronaut.lifecycle.graceful-shutdown.grace-period");
    }
}
