package io.github.excalibase.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphqlSpanHandlerTest {

    private final RecordingSpans exported = new RecordingSpans();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exported)).build()).build();
    private final ObservationRegistry registry = ObservationRegistry.create();

    GraphqlSpanHandlerTest() {
        registry.observationConfig().observationHandler(new GraphqlSpanHandler(sdk.getTracer("test")));
    }

    @AfterEach
    void close() {
        sdk.close();
    }

    private Observation graphql(String name) {
        return Observation.createNotStarted("graphql.request", registry)
                .lowCardinalityKeyValue("operation.type", "query").highCardinalityKeyValue("operation.name", name);
    }

    @Test
    @DisplayName("a graphql.request observation is one INTERNAL span carrying the operation type and name")
    void spanPerRequest() {
        Observation observation = graphql("anonymous").start();
        observation.stop();

        SpanData span = exported.spans().get(0);
        assertThat(span.getName()).isEqualTo("graphql.request");
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getAttributes().asMap()).containsOnly(
                Map.entry(AttributeKey.stringKey("operation.type"), "query"),
                Map.entry(AttributeKey.stringKey("operation.name"), "anonymous"));
    }

    @Test
    @DisplayName("the span is a child of the request's current span")
    void childOfCurrent() {
        Span server = sdk.getTracer("test").spanBuilder("http post").startSpan();
        try (Scope ignored = server.makeCurrent()) {
            graphql("op").start().stop();
        }
        server.end();

        SpanData span = exported.spans().stream().filter(candidate -> candidate.getName().equals("graphql.request")).findFirst().orElseThrow();
        assertThat(span.getParentSpanId()).isEqualTo(server.getSpanContext().getSpanId());
    }

    @Test
    @DisplayName("a failed observation marks the span as an error and records the exception")
    void errorRecorded() {
        Observation observation = graphql("op").start();
        observation.error(new IllegalStateException("boom"));
        observation.stop();

        SpanData span = exported.spans().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).extracting(event -> event.getName()).containsExactly("exception");
    }

    @Test
    @DisplayName("other observations make no span")
    void otherObservationsIgnored() {
        Observation.createNotStarted("something.else", registry).start().stop();

        assertThat(exported.spans()).isEmpty();
        assertThat(List.of(Span.current().getSpanContext().isValid())).containsExactly(false);
    }
}
