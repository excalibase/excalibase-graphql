package io.github.excalibase.observability;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphQLObservabilityInstrumentationTest {

    private MeterRegistry meterRegistry;
    private GraphQLObservabilityInstrumentation instrumentation;

    @Mock
    private InstrumentationExecutionParameters params;

    @BeforeEach
    void setUp() {
        // SimpleMeterRegistry keeps everything in-memory — no OTLP needed in tests
        meterRegistry = new SimpleMeterRegistry();

        // Wire a real ObservationRegistry so Observation.stop() writes into SimpleMeterRegistry
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        instrumentation = new GraphQLObservabilityInstrumentation(observationRegistry, meterRegistry);
    }

    // ── operation type detection ─────────────────────────────────────────────

    @ParameterizedTest(name = "query=''{0}'' → type=''{1}''")
    @CsvSource({
        "query GetUsers { users { id } },     query",
        "{ users { id } },                    query",
        "mutation CreateUser { createUser },  mutation",
        "MUTATION CreateUser { createUser },  mutation",
        "subscription OnUser { onUser },      subscription",
        "  subscription OnUser { onUser },    subscription",   // leading whitespace
        ",                                    query",           // null query
    })
    void detectsOperationType(String query, String expectedType) {
        when(params.getQuery()).thenReturn(query);
        when(params.getOperation()).thenReturn(null);

        InstrumentationContext<ExecutionResult> ctx = instrumentation.beginExecution(params, null);

        // Complete without errors — timer should be recorded
        ExecutionResult result = mock(ExecutionResult.class);
        when(result.getErrors()).thenReturn(List.of());
        ctx.onCompleted(result, null);

        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", expectedType)
                .timer())
                .as("timer for operation.type=%s", expectedType)
                .isNotNull();
    }

    // ── error counter ─────────────────────────────────────────────────────────

    @Test
    void incrementsErrorCounterWhenResponseHasErrors() {
        when(params.getQuery()).thenReturn("query { users { id } }");
        when(params.getOperation()).thenReturn("GetUsers");

        InstrumentationContext<ExecutionResult> ctx = instrumentation.beginExecution(params, null);

        ExecutionResult result = mock(ExecutionResult.class);
        GraphQLError error = mock(GraphQLError.class);
        when(result.getErrors()).thenReturn(List.of(error, error)); // 2 errors

        ctx.onCompleted(result, null);

        Counter errorCounter = meterRegistry.find("graphql.request.errors")
                .tag("operation.type", "query")
                .counter();
        assertThat(errorCounter).isNotNull();
        assertThat(errorCounter.count()).isEqualTo(2.0);
    }

    @Test
    void doesNotIncrementErrorCounterOnSuccess() {
        when(params.getQuery()).thenReturn("{ users { id } }");
        when(params.getOperation()).thenReturn(null);

        InstrumentationContext<ExecutionResult> ctx = instrumentation.beginExecution(params, null);

        ExecutionResult result = mock(ExecutionResult.class);
        when(result.getErrors()).thenReturn(List.of());
        ctx.onCompleted(result, null);

        assertThat(meterRegistry.find("graphql.request.errors").counter()).isNull();
    }

    // ── exception path ────────────────────────────────────────────────────────

    @Test
    void handlesThrowableWithoutNullPointer() {
        when(params.getQuery()).thenReturn("mutation { createUser }");
        when(params.getOperation()).thenReturn("CreateUser");

        InstrumentationContext<ExecutionResult> ctx = instrumentation.beginExecution(params, null);

        // onCompleted with a Throwable and null result — must not throw
        ctx.onCompleted(null, new RuntimeException("db error"));

        // timer should still be recorded
        assertThat(meterRegistry.find("graphql.request")
                .tag("operation.type", "mutation")
                .timer())
                .isNotNull();
    }
}
