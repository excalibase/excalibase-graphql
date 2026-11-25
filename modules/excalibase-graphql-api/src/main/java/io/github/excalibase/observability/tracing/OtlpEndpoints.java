package io.github.excalibase.observability.tracing;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Where signals are exported: the collector named by {@code OTEL_EXPORTER_OTLP_ENDPOINT} (a signal's path is added),
 * or a signal's own {@code management.otlp.*} endpoint, which wins. With neither, that signal is not exported at all
 * instead of being posted to localhost and failing on every flush.
 */
@Singleton
public class OtlpEndpoints {

    private final String collector;
    private final String tracing;

    public OtlpEndpoints(@Value("${otel.exporter.otlp.endpoint:}") String collector,
                         @Value("${management.otlp.tracing.endpoint:}") String tracing) {
        this.collector = withoutTrailingSlash(collector.strip());
        this.tracing = tracing.strip();
    }

    public Optional<String> traces() {
        if (!tracing.isEmpty()) {
            return Optional.of(tracing);
        }
        return collector.isEmpty() ? Optional.empty() : Optional.of(collector + "/v1/traces");
    }

    private static String withoutTrailingSlash(String endpoint) {
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
