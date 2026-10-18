package io.github.excalibase.observability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.aot.AotDetector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Exports metrics, traces and logs over OTLP only to a configured collector:
 * OTEL_EXPORTER_OTLP_ENDPOINT, or a signal's own management.otlp.* endpoint.
 * Without one that signal stays off instead of posting to localhost and
 * logging a failure on every flush.
 *
 * <p>The native image freezes bean conditions at build time, so the AOT build
 * keeps every exporter, and at native runtime a signal without a collector is
 * silenced through settings read at startup (metrics publishing off, traces
 * not sampled).
 */
public class OtlpExportEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String ENDPOINT_PROPERTY = "OTEL_EXPORTER_OTLP_ENDPOINT";
    private static final String AOT_PROCESSING = "spring.aot.processing";
    private static final String UNUSED_ENDPOINT = "http://localhost:4318";
    private static final String DEFAULTS_SOURCE = "excalibaseOtlpExport";
    private static final String OFF_SOURCE = "excalibaseOtlpExportOff";

    private enum Signal {
        METRICS("management.otlp.metrics.export.url", "management.otlp.metrics.export.enabled", "/v1/metrics"),
        TRACING("management.otlp.tracing.endpoint", "management.otlp.tracing.export.enabled", "/v1/traces"),
        LOGGING("management.otlp.logging.endpoint", "management.otlp.logging.export.enabled", "/v1/logs");

        final String endpointKey;
        final String enabledKey;
        final String path;

        Signal(String endpointKey, String enabledKey, String path) {
            this.endpointKey = endpointKey;
            this.enabledKey = enabledKey;
            this.path = path;
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String collector = trimTrailingSlash(environment.getProperty(ENDPOINT_PROPERTY, "").strip());
        boolean aotBuild = Boolean.parseBoolean(System.getProperty(AOT_PROCESSING));
        boolean nativeRuntime = !aotBuild && AotDetector.useGeneratedArtifacts();
        Map<String, Object> defaults = new HashMap<>();
        Map<String, Object> off = new HashMap<>();
        for (Signal signal : Signal.values()) {
            if (hasText(environment.getProperty(signal.endpointKey))) {
                continue;
            }
            if (!collector.isEmpty()) {
                defaults.put(signal.endpointKey, collector + signal.path);
            } else if (aotBuild) {
                defaults.put(signal.endpointKey, UNUSED_ENDPOINT + signal.path);
            } else {
                silence(signal, nativeRuntime, defaults, off);
            }
        }
        // Defaults last, so explicit settings win; "off" first, so it beats the yaml's sampling default.
        environment.getPropertySources().addLast(new MapPropertySource(DEFAULTS_SOURCE, defaults));
        environment.getPropertySources().addFirst(new MapPropertySource(OFF_SOURCE, off));
    }

    private static void silence(Signal signal, boolean nativeRuntime, Map<String, Object> defaults, Map<String, Object> off) {
        off.put(signal.enabledKey, "false");
        if (!nativeRuntime) {
            return;
        }
        // The native image built this exporter anyway; it needs an address and must never be handed data.
        defaults.put(signal.endpointKey, UNUSED_ENDPOINT + signal.path);
        if (signal == Signal.TRACING) {
            off.put("management.tracing.sampling.probability", "0");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
