package io.github.excalibase.observability.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TracingFactoryTest {

    @Test
    @DisplayName("without an endpoint no OTLP exporter is built")
    void withoutAnEndpointNoOtlpExporterIsBuilt() {
        try (OpenTelemetrySdk sdk = new TracingFactory().openTelemetry(List.of(), new OtlpEndpoints("", ""), 1.0, "svc")) {
            assertThat(sdk.getSdkTracerProvider().toString()).doesNotContain("OtlpHttpSpanExporter");
        }
    }

    @Test
    @DisplayName("with an endpoint the OTLP span exporter is built")
    void withAnEndpointTheOtlpExporterIsBuilt() {
        try (OpenTelemetrySdk sdk = new TracingFactory().openTelemetry(List.of(),
                new OtlpEndpoints("http://collector:4318", ""), 1.0, "svc")) {
            assertThat(sdk.getSdkTracerProvider().toString()).contains("OtlpHttpSpanExporter");
        }
    }

    @Test
    @DisplayName("without a processor or an endpoint a started span still has a valid trace id for the logs")
    void spanWithoutAnyProcessorStillHasTraceIds() {
        try (OpenTelemetrySdk sdk = new TracingFactory().openTelemetry(List.of(), new OtlpEndpoints("", ""), 1.0, "svc")) {
            Span span = sdk.getTracer("t").spanBuilder("request").startSpan();
            try {
                assertThat(span.getSpanContext().isValid()).isTrue();
            } finally {
                span.end();
            }
        }
    }

    @Test
    @DisplayName("application.yml names no default collector")
    void applicationYamlNamesNoDefaultCollector() throws IOException {
        try (InputStream yaml = getClass().getResourceAsStream("/application.yml")) {
            assertThat(yaml).isNotNull();
            assertThat(new String(yaml.readAllBytes(), StandardCharsets.UTF_8)).doesNotContain("localhost:4318");
        }
    }
}
