package io.github.excalibase.observability;

import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * GraphQL instrumentation that emits OpenTelemetry traces and Micrometer metrics
 * for every GraphQL execution via the {@link ObservationRegistry} abstraction.
 *
 * <p>When {@code micrometer-tracing-bridge-otel} is on the classpath each
 * {@link Observation} automatically produces:
 * <ul>
 *   <li>A timer metric  — {@code graphql.request} tagged by {@code operation.type}</li>
 *   <li>A counter       — {@code graphql.request.errors} when the response contains errors</li>
 *   <li>An OTel span    — named {@code graphql.<type>}, carrying the operation name as a
 *                         high-cardinality attribute (span-only, not a metric tag)</li>
 * </ul>
 */
public class GraphQLObservabilityInstrumentation extends SimplePerformantInstrumentation {

    private static final String METRIC_REQUEST = "graphql.request";
    private static final String METRIC_ERRORS  = "graphql.request.errors";

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public GraphQLObservabilityInstrumentation(ObservationRegistry observationRegistry,
                                               MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(
            InstrumentationExecutionParameters params,
            graphql.execution.instrumentation.InstrumentationState state) {

        String operationType = detectOperationType(params.getQuery());
        String operationName = params.getOperation() != null ? params.getOperation() : "anonymous";

        Observation observation = Observation.createNotStarted(METRIC_REQUEST, observationRegistry)
                // low-cardinality → goes into metric tags AND span tags (3 possible values)
                .lowCardinalityKeyValue("operation.type", operationType)
                // high-cardinality → span attribute only, NOT a metric tag (prevents cardinality explosion)
                .highCardinalityKeyValue("operation.name", operationName)
                .start();

        return new SimpleInstrumentationContext<>() {
            @Override
            public void onCompleted(ExecutionResult result, Throwable t) {
                if (t != null) {
                    observation.error(t);
                }
                if (result != null && !result.getErrors().isEmpty()) {
                    observation.event(Observation.Event.of("graphql.error"));
                    meterRegistry.counter(METRIC_ERRORS,
                            "operation.type", operationType).increment(result.getErrors().size());
                }
                observation.stop();
            }
        };
    }

    /** Detects the GraphQL operation type from the raw query string. */
    private String detectOperationType(String query) {
        if (query == null) return "query";
        String trimmed = query.stripLeading().toLowerCase();
        if (trimmed.startsWith("mutation"))     return "mutation";
        if (trimmed.startsWith("subscription")) return "subscription";
        return "query";
    }
}
