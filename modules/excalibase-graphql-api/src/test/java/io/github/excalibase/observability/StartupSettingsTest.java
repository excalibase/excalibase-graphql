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
}
