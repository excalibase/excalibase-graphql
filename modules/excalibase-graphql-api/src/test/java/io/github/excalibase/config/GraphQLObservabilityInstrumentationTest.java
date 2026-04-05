package io.github.excalibase.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQLObservabilityInstrumentationTest {

    private MeterRegistry meterRegistry;
    private GraphQLObservabilityInstrumentation instrumentation;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        instrumentation = new GraphQLObservabilityInstrumentation(observationRegistry, meterRegistry);
    }

    @ParameterizedTest(name = "query=''{0}'' -> type=''{1}''")
    @CsvSource({
        "query GetUsers { users { id } },     query",
        "{ users { id } },                    query",
        "mutation CreateUser { createUser },  mutation",
        "MUTATION CreateUser { createUser },  mutation",
        "subscription OnUser { onUser },      subscription",
        "  subscription OnUser { onUser },    subscription",
    })
    void detectsOperationType(String query, String expectedType) {
        instrumentation.observe(query, null, () ->
                ResponseEntity.ok(Map.of("data", Map.of())));

        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", expectedType)
                .timer())
                .as("timer for operation.type=%s", expectedType)
                .isNotNull();
    }

    @Test
    void nullQuery_defaultsToQueryType() {
        instrumentation.observe(null, null, () ->
                ResponseEntity.ok(Map.of("data", Map.of())));

        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", "query")
                .timer())
                .isNotNull();
    }

    @Test
    void incrementsErrorCounterWhenResponseHasErrors() {
        instrumentation.observe("query { users { id } }", "GetUsers", () ->
                ResponseEntity.ok(Map.of("errors", List.of(
                        Map.of("message", "err1"),
                        Map.of("message", "err2")))));

        Counter errorCounter = meterRegistry.find("graphql.request.errors")
                .tag("operation.type", "query")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(2.0);
    }

    @Test
    void doesNotIncrementErrorCounterOnSuccess() {
        instrumentation.observe("{ users { id } }", null, () ->
                ResponseEntity.ok(Map.of("data", Map.of())));

        assertThat(meterRegistry.find("graphql.request.errors").counter()).isNull();
    }

    @Test
    void recordsTimerEvenWhenExceptionThrown() {
        assertThatThrownBy(() ->
                instrumentation.observe("mutation { create }", "Create", () -> {
                    throw new RuntimeException("db error");
                }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db error");

        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", "mutation")
                .timer())
                .isNotNull();
    }

    @Test
    void usesOperationNameAsHighCardinalityTag() {
        instrumentation.observe("query GetUsers { users { id } }", "GetUsers", () ->
                ResponseEntity.ok(Map.of("data", Map.of())));

        // High-cardinality tags only go to spans, not metric tags.
        // Timer should exist tagged by operation.type only.
        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", "query")
                .timer())
                .isNotNull();
    }
}
