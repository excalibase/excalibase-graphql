package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class OtlpExportEnvironmentPostProcessorTest {

    private static final String ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> new OtlpExportEnvironmentPostProcessor()
                    .postProcessEnvironment(context.getEnvironment(), null))
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class,
                    MetricsAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class,
                    OpenTelemetryAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class));

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new OtlpExportEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }

    @Test
    void noEndpointTurnsEveryOtlpExportOff() {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.otlp.logging.export.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.otlp.tracing.endpoint")).isNull();
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isNull();
        assertThat(environment.getProperty("management.otlp.logging.endpoint")).isNull();
    }

    @Test
    void blankEndpointCountsAsNone() {
        StandardEnvironment environment = environment(Map.of(ENDPOINT, "  "));

        assertThat(environment.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false");
    }

    @Test
    void configuredEndpointExportsEverySignalThere() {
        StandardEnvironment environment = environment(Map.of(ENDPOINT, "http://collector:4318/"));

        assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isNotEqualTo("false");
        assertThat(environment.getProperty("management.otlp.tracing.export.enabled")).isNotEqualTo("false");
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isEqualTo("http://collector:4318/v1/metrics");
        assertThat(environment.getProperty("management.otlp.tracing.endpoint")).isEqualTo("http://collector:4318/v1/traces");
        assertThat(environment.getProperty("management.otlp.logging.endpoint")).isEqualTo("http://collector:4318/v1/logs");
    }

    @Test
    void anExplicitSignalEndpointWins() {
        StandardEnvironment environment = environment(Map.of(ENDPOINT, "http://collector:4318",
                "management.otlp.tracing.endpoint", "http://tempo:4318/v1/traces"));

        assertThat(environment.getProperty("management.otlp.tracing.endpoint")).isEqualTo("http://tempo:4318/v1/traces");
    }

    @Test
    void withoutAnEndpointNoOtlpExporterIsBuilt() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
            assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
        });
    }

    @Test
    void withAnEndpointTheOtlpExportersAreBuilt() {
        runner.withSystemProperties(ENDPOINT + "=http://collector:4318").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
            assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
        });
    }

    @Test
    void applicationYamlNamesNoDefaultCollector() throws IOException {
        try (InputStream yaml = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(yaml).isNotNull();
            assertThat(new String(yaml.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("localhost:4318");
        }
    }
}
