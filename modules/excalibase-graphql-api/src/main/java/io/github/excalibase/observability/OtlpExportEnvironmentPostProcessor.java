package io.github.excalibase.observability;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Exports metrics, traces and logs over OTLP only when OTEL_EXPORTER_OTLP_ENDPOINT
 * names a collector; without one every OTLP exporter stays off instead of
 * posting to localhost and logging a failure on every flush.
 */
public class OtlpExportEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String ENDPOINT_PROPERTY = "OTEL_EXPORTER_OTLP_ENDPOINT";
    private static final String SOURCE_NAME = "excalibaseOtlpExport";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String endpoint = environment.getProperty(ENDPOINT_PROPERTY, "").strip();
        Map<String, Object> properties = endpoint.isEmpty() ? exportOff() : exportTo(trimTrailingSlash(endpoint));
        // Last: an explicit management.otlp.* setting still wins.
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, properties));
    }

    private static Map<String, Object> exportOff() {
        return Map.of(
                "management.otlp.metrics.export.enabled", "false",
                "management.otlp.tracing.export.enabled", "false",
                "management.otlp.logging.export.enabled", "false");
    }

    private static Map<String, Object> exportTo(String endpoint) {
        return Map.of(
                "management.otlp.metrics.export.url", endpoint + "/v1/metrics",
                "management.otlp.tracing.endpoint", endpoint + "/v1/traces",
                "management.otlp.logging.endpoint", endpoint + "/v1/logs");
    }

    private static String trimTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
