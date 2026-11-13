package io.github.excalibase.observability.tracing;

import io.micronaut.core.propagation.ThreadPropagatedContextElement;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

/**
 * Carries a request's span to whichever thread runs the handler: the span is current there, and its ids are in the
 * log context for the duration, so every log line of the request names its trace.
 */
public record SpanContextElement(Span span) implements ThreadPropagatedContextElement<SpanContextElement.Saved> {

    static final String TRACE_ID = "traceId";
    static final String SPAN_ID = "spanId";

    /** What the thread held before: the scope to close and the ids that were in the log context. */
    public record Saved(Scope scope, String traceId, String spanId) {}

    @Override
    public Saved updateThreadContext() {
        Saved before = new Saved(Context.current().with(span).makeCurrent(), MDC.get(TRACE_ID), MDC.get(SPAN_ID));
        if (span.getSpanContext().isValid()) {
            MDC.put(TRACE_ID, span.getSpanContext().getTraceId());
            MDC.put(SPAN_ID, span.getSpanContext().getSpanId());
        } else {
            MDC.remove(TRACE_ID);
            MDC.remove(SPAN_ID);
        }
        return before;
    }

    @Override
    public void restoreThreadContext(Saved before) {
        before.scope().close();
        restore(TRACE_ID, before.traceId());
        restore(SPAN_ID, before.spanId());
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
