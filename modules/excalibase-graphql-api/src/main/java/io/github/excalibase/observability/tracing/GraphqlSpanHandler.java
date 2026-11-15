package io.github.excalibase.observability.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/** Turns each {@code graphql.request} observation into a span under the request's server span. */
public final class GraphqlSpanHandler implements ObservationHandler<Observation.Context> {

    static final String OBSERVATION = "graphql.request";

    private final Tracer tracer;

    public GraphqlSpanHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return OBSERVATION.equals(context.getName());
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(Span.class, tracer.spanBuilder(OBSERVATION).startSpan());
    }

    @Override
    public void onError(Observation.Context context) {
        Span span = context.get(Span.class);
        if (span != null && context.getError() != null) {
            span.recordException(context.getError());
            span.setStatus(StatusCode.ERROR);
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        Span span = context.get(Span.class);
        if (span == null) {
            return;
        }
        for (KeyValue keyValue : context.getAllKeyValues()) {
            span.setAttribute(AttributeKey.stringKey(keyValue.getKey()), keyValue.getValue());
        }
        span.end();
    }
}
