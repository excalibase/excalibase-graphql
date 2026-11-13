package io.github.excalibase.observability;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings computed from the process environment before the application starts: OTLP metric export (only to a
 * configured collector) and logger levels from {@code LOGGING_LEVEL_*}, the variables the earlier build read.
 */
public final class StartupSettings {

    private static final String COLLECTOR = "OTEL_EXPORTER_OTLP_ENDPOINT";
    private static final String METRICS_URL = "MANAGEMENT_OTLP_METRICS_EXPORT_URL";
    // The spelling the deploy sets; the framework's own relaxed binding would read it as percentileshistogram.
    private static final String HISTOGRAM_VARIABLE = "MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS";
    private static final String HISTOGRAM_PROPERTY = "management.metrics.distribution.percentiles-histogram.http.server.requests";
    private static final String LEVEL_PREFIX = "LOGGING_LEVEL_";
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
    private static final Map<String, String> LEGACY_LEVEL_VARIABLES = Map.of(
            "LOG_LEVEL_ROOT", "ROOT", "LOG_LEVEL_APP", "io.github.excalibase", "LOG_LEVEL_SPRING", "org.springframework");

    private StartupSettings() {
    }

    public static Map<String, Object> from(Map<String, String> env) {
        requireKnownLogFormat(env.get("LOG_FORMAT"));
        Map<String, Object> settings = new HashMap<>(metricsExport(env));
        settings.putAll(loggerLevels(env));
        histogram(env).ifPresent(value -> settings.put(HISTOGRAM_PROPERTY, value));
        return settings;
    }

    /**
     * Sets the levels named in {@code settings} on the logging system itself. The framework applies the same
     * settings while the context starts, by which time some beans have already logged.
     */
    public static void applyLoggerLevels(Map<String, Object> settings) {
        String prefix = "logger.levels.";
        settings.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                String name = key.substring(prefix.length());
                Logger logger = LoggerFactory.getLogger(name.equals("ROOT") ? Logger.ROOT_LOGGER_NAME : name);
                if (logger instanceof ch.qos.logback.classic.Logger logback) {
                    logback.setLevel(ch.qos.logback.classic.Level.toLevel(String.valueOf(value)));
                }
            }
        });
    }

    private static void requireKnownLogFormat(String format) {
        if (format != null && !format.isBlank() && !format.strip().equalsIgnoreCase("ecs")) {
            throw new IllegalArgumentException("Unknown format '" + format.strip() + "' in LOG_FORMAT: ecs is the one log format");
        }
    }

    private static Map<String, Object> metricsExport(Map<String, String> env) {
        String own = env.getOrDefault(METRICS_URL, "").strip();
        String collector = withoutTrailingSlash(env.getOrDefault(COLLECTOR, "").strip());
        String url = !own.isEmpty() ? own : collector.isEmpty() ? "" : collector + "/v1/metrics";
        if (url.isEmpty()) {
            return Map.of("micronaut.metrics.export.otlp.enabled", "false");
        }
        return Map.of("micronaut.metrics.export.otlp.enabled", "true", "micronaut.metrics.export.otlp.url", url);
    }

    private static Optional<String> histogram(Map<String, String> env) {
        String value = env.getOrDefault(HISTOGRAM_VARIABLE, "").strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(HISTOGRAM_VARIABLE + " must be true or false but was '" + value + "'");
        }
        return Optional.of(value.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> loggerLevels(Map<String, String> env) {
        Map<String, Object> levels = new HashMap<>();
        LEGACY_LEVEL_VARIABLES.forEach((variable, logger) -> {
            if (env.containsKey(variable)) {
                levels.put("logger.levels." + logger, level(variable, env.get(variable)));
            }
        });
        env.forEach((variable, value) -> {
            if (variable.startsWith(LEVEL_PREFIX) && variable.length() > LEVEL_PREFIX.length()) {
                levels.put("logger.levels." + loggerName(variable.substring(LEVEL_PREFIX.length())), level(variable, value));
            }
        });
        return levels;
    }

    private static String loggerName(String suffix) {
        return suffix.equals("ROOT") ? "ROOT" : suffix.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static String level(String variable, String value) {
        String level = value.strip().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException(variable + " must be one of trace, debug, info, warn, error, off but was '" + value + "'");
        }
        return level;
    }

    private static String withoutTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
