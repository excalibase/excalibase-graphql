package io.github.excalibase.observability.tracing;

import io.github.excalibase.observability.HttpRequestFacts;
import io.github.excalibase.observability.ObservabilityLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.propagation.MutablePropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.Map;
import java.util.Locale;

/**
 * One SERVER span per request, a child of the caller's span when it sent a {@code traceparent}, named after the
 * method and route template and labelled like the {@code http.server.requests} timer. The span is current on every
 * thread that runs the request, so log lines and the GraphQL span below it belong to the trace. Only at level
 * {@code full}.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class HttpServerTracingFilter implements Ordered {

    private static final String SPAN = "excalibase.tracing.span";

    private static final TextMapGetter<HttpRequest<?>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpRequest<?> request) {
            return request.getHeaders().names();
        }

        @Override
        public String get(@Nullable HttpRequest<?> request, String key) {
            return request == null ? null : request.getHeaders().get(key);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final ObservabilityLevel level;

    public HttpServerTracingFilter(Tracer tracer, OpenTelemetry openTelemetry, ObservabilityLevel level) {
        this.tracer = tracer;
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.level = level;
    }

    @Override
    public int getOrder() {
        return -1500;
    }

    @RequestFilter
    public void start(HttpRequest<?> request, MutablePropagatedContext propagated) {
        if (!level.tracesRequests()) {
            return;
        }
        Context caller = propagator.extract(Context.root(), request, HEADERS);
        Span span = tracer.spanBuilder("http " + HttpRequestFacts.method(request).toLowerCase(Locale.ROOT))
                .setParent(caller).setSpanKind(SpanKind.SERVER).startSpan();
        request.setAttribute(SPAN, span);
        propagated.add(new SpanContextElement(span));
    }

    @ResponseFilter
    public void end(HttpRequest<?> request, HttpResponse<?> response, @Nullable Throwable thrown) {
        if (!(request.getAttribute(SPAN).orElse(null) instanceof Span span)) {
            return;
        }
        HttpRequestFacts facts = HttpRequestFacts.of(request, response.code(), thrown);
        span.updateName(facts.spanName());
        describe(span, facts, request);
        Throwable failure = thrown != null ? thrown : request.getAttribute(HttpRequestFacts.EXCEPTION_ATTRIBUTE, Throwable.class).orElse(null);
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
        }
        span.end();
    }

    private static void describe(Span span, HttpRequestFacts facts, HttpRequest<?> request) {
        Map.of("exception", facts.exception(),
                "method", facts.method(),
                "outcome", facts.outcome(),
                "status", Integer.toString(facts.status()),
                "uri", facts.uri(),
                "tenant.id", facts.tenant().tenantId(),
                "org.slug", facts.tenant().orgSlug(),
                "project.name", facts.tenant().projectName(),
                "org.name", facts.tenant().orgName(),
                "http.url", request.getPath())
                .forEach((key, value) -> span.setAttribute(AttributeKey.stringKey(key), value));
    }
}
