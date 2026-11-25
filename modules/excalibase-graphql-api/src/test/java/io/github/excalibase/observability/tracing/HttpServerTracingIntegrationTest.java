package io.github.excalibase.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.SpanProcessor;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class HttpServerTracingIntegrationTest extends EngineIntegrationTest {

    static final RecordingSpans SPANS = new RecordingSpans();
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String CALLER_SPAN = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + CALLER_SPAN + "-01";

    @Factory
    static class Recorder {
        @Singleton
        SpanProcessor recording() {
            return SimpleSpanProcessor.create(SPANS);
        }
    }

    @Controller("/{projectId}/probe")
    static class LogProbe {
        @Get("/ids")
        String ids() {
            return MDC.get("traceId") + "," + MDC.get("spanId");
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.jwt-enabled", "false",
                "app.security.insecure-dev-mode", "true",
                "endpoints.all.port", Integer.toString(FreePort.find()));
    }

    @BeforeEach
    void forget() {
        SPANS.clear();
    }

    private SpanData serverSpan(String name) {
        return Awaitility.await().until(() -> SPANS.spans().stream()
                .filter(span -> span.getName().equals(name)).findFirst().orElse(null), span -> span != null);
    }

    @Test
    @DisplayName("a request makes one SERVER span named after the route template, child of the caller's span")
    void serverSpanContinuesTheCaller() {
        http.get("test-proj/probe/ids", "traceparent", TRACEPARENT);

        SpanData span = serverSpan("http get /{projectId}/probe/ids");

        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(span.getParentSpanId()).isEqualTo(CALLER_SPAN);
        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo("org.springframework.boot");
    }

    @Test
    @DisplayName("the span carries the request attributes of the earlier build, tenant labels 'none' when there is no tenant")
    void serverSpanAttributes() {
        http.get("test-proj/probe/ids", "traceparent", TRACEPARENT);

        Map<String, String> attributes = serverSpan("http get /{projectId}/probe/ids").getAttributes().asMap().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().getKey(), entry -> String.valueOf(entry.getValue())));

        assertThat(attributes).containsEntry("method", "GET").containsEntry("status", "200")
                .containsEntry("uri", "/{projectId}/probe/ids").containsEntry("outcome", "SUCCESS").containsEntry("exception", "none")
                .containsEntry("tenant.id", "none").containsEntry("org.slug", "none")
                .containsEntry("project.name", "none").containsEntry("org.name", "none")
                .containsKey("http.url");
    }

    @Test
    @DisplayName("a request no route matches is named after the catch-all, as the earlier build named it")
    void unmatchedRequest() {
        http.get("test-proj/nothing/here", "traceparent", TRACEPARENT);

        SpanData span = serverSpan("http get /**");

        assertThat(span.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("404");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("CLIENT_ERROR");
    }

    @Test
    @DisplayName("the handler logs with the trace and span id of the request's server span")
    void logContextCarriesIds() {
        String body = http.get("test-proj/probe/ids", "traceparent", TRACEPARENT).body();

        SpanData span = serverSpan("http get /{projectId}/probe/ids");

        assertThat(body).isEqualTo(TRACE_ID + "," + span.getSpanId());
    }

    @Test
    @DisplayName("without a caller's trace the request still gets a trace, so log lines carry ids")
    void rootSpanWithoutCaller() {
        String body = http.get("test-proj/probe/ids").body();

        SpanData span = serverSpan("http get /{projectId}/probe/ids");

        assertThat(span.getParentSpanContext().isValid()).isFalse();
        assertThat(body).isEqualTo(span.getTraceId() + "," + span.getSpanId());
    }
}
