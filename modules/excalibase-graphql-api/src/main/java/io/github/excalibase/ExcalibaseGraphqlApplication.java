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

    /** The per-project request metrics are served on their own port, opened only when they are enabled. */
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
        return properties;
    }
}
