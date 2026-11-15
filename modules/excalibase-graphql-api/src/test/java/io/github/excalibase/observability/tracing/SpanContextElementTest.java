package io.github.excalibase.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class SpanContextElementTest {

    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(
            SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new RecordingSpans())).build()).build();
    private final Tracer tracer = sdk.getTracer("test");

    @AfterEach
    void close() {
        MDC.clear();
        sdk.close();
    }

    @Test
    @DisplayName("while the element is applied the span is current and its ids are in the log context")
    void appliesSpanAndMdc() {
        Span span = tracer.spanBuilder("request").startSpan();
        SpanContextElement element = new SpanContextElement(span);

        SpanContextElement.Saved saved = element.updateThreadContext();

        assertThat(Span.current()).isEqualTo(span);
        assertThat(MDC.get("traceId")).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(MDC.get("spanId")).isEqualTo(span.getSpanContext().getSpanId());
        element.restoreThreadContext(saved);
    }

    @Test
    @DisplayName("after restore the thread holds what it held before, a parent span included")
    void restoresPrevious() {
        Span outer = tracer.spanBuilder("outer").startSpan();
        SpanContextElement.Saved outerSaved = new SpanContextElement(outer).updateThreadContext();
        Span inner = tracer.spanBuilder("inner").startSpan();
        SpanContextElement innerElement = new SpanContextElement(inner);

        SpanContextElement.Saved innerSaved = innerElement.updateThreadContext();
        innerElement.restoreThreadContext(innerSaved);

        assertThat(Span.current()).isEqualTo(outer);
        assertThat(MDC.get("spanId")).isEqualTo(outer.getSpanContext().getSpanId());
        new SpanContextElement(outer).restoreThreadContext(outerSaved);
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }

    @Test
    @DisplayName("an unsampled or invalid span puts no ids in the log context")
    void noIdsWithoutValidSpan() {
        SpanContextElement element = new SpanContextElement(Span.getInvalid());

        SpanContextElement.Saved saved = element.updateThreadContext();

        assertThat(MDC.get("traceId")).isNull();
        element.restoreThreadContext(saved);
    }
}
