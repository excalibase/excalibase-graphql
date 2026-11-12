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
        if (Boolean.parseBoolean(env.getOrDefault("APP_REQUEST_METRICS_ENABLED", "false"))) {
            String address = env.getOrDefault("APP_REQUEST_METRICS_ADDRESS", "").strip();
            properties.put("micronaut.server.netty.listeners.metrics.port",
                    Integer.parseInt(env.getOrDefault("APP_REQUEST_METRICS_PORT", "9464")));
            if (!address.isEmpty()) {
                properties.put("micronaut.server.netty.listeners.metrics.host", address);
            }
        }
        // Exports go only to a configured collector; without one no signal is exported or retried.
        if (env.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "").isBlank()) {
            properties.put("otel.traces.exporter", "none");
            properties.put("otel.metrics.exporter", "none");
            properties.put("otel.logs.exporter", "none");
        }
        return properties;
    }
}
