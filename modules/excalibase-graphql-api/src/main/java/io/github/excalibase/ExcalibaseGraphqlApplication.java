package io.github.excalibase;

import io.micronaut.runtime.Micronaut;

import java.util.HashMap;
import java.util.Map;

public class ExcalibaseGraphqlApplication {

    public static void main(String[] args) {
        Micronaut.build(args)
                .mainClass(ExcalibaseGraphqlApplication.class)
                .properties(internalListeners(System.getenv()))
                .start();
    }

    /** Settings computed from the environment: the request-metrics port (opened only when enabled) and OTLP export (only with a collector). */
    static Map<String, Object> internalListeners(Map<String, String> env) {
        Map<String, Object> properties = new HashMap<>();
        // Exports go only to a configured collector; without one no signal is exported or retried.
        String collector = env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip();
        if (!collector.isEmpty()) {
            String base = collector.endsWith("/") ? collector.substring(0, collector.length() - 1) : collector;
            properties.put("micronaut.metrics.export.otlp.enabled", "true");
            properties.put("micronaut.metrics.export.otlp.url", base + "/v1/metrics");
        } else {
            properties.put("otel.traces.exporter", "none");
            properties.put("otel.metrics.exporter", "none");
            properties.put("otel.logs.exporter", "none");
        }
        return properties;
    }
}
