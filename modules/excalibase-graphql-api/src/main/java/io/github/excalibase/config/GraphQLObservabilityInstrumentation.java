package io.github.excalibase.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * GraphQL observability: wraps each request with a Micrometer {@link Observation}
 * that produces an OTel span and metrics.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code graphql.request} timer — tagged by {@code operation.type}</li>
 *   <li>{@code graphql.request.errors} counter — incremented when the response contains errors</li>
 * </ul>
 */
@Component
public class GraphQLObservabilityInstrumentation {

    private static final String METRIC_REQUEST = "graphql.request";
    private static final String METRIC_ERRORS = "graphql.request.errors";

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public GraphQLObservabilityInstrumentation(ObservationRegistry observationRegistry,
                                               MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Execute a GraphQL request within an observation scope.
     *
     * @param query     the raw GraphQL query string
     * @param operation the operation name (nullable)
     * @param action    the actual execution logic
     * @return the response from the action
     */
    public ResponseEntity<Object> observe(String query, String operation,
                                          Supplier<ResponseEntity<Object>> action) {
        String operationType = detectOperationType(query);
        String operationName = operation != null ? operation : "anonymous";

        Observation observation = Observation.createNotStarted(METRIC_REQUEST, observationRegistry)
                .lowCardinalityKeyValue("operation.type", operationType)
                .highCardinalityKeyValue("operation.name", operationName)
                .start();

        try {
            ResponseEntity<Object> response = action.get();
            countErrors(response, operationType);
            return response;
        } catch (Exception e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private void countErrors(ResponseEntity<Object> response, String operationType) {
        if (response.getBody() instanceof Map<?, ?> body
                && body.get("errors") instanceof List<?> errors
                && !errors.isEmpty()) {
            meterRegistry.counter(METRIC_ERRORS, "operation.type", operationType)
                    .increment(errors.size());
        }
    }

    private static String detectOperationType(String query) {
        if (query == null) return "query";
        String trimmed = query.stripLeading().toLowerCase();
        if (trimmed.startsWith("mutation")) return "mutation";
        if (trimmed.startsWith("subscription")) return "subscription";
        return "query";
    }
}
