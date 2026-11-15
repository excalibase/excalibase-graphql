package io.github.excalibase.observability.tracing;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The OpenTelemetry SDK behind every span the engine makes. Spans are always created, so log lines carry trace ids
 * even without a collector; they leave the process only when {@link OtlpEndpoints} names one.
 */
@Factory
class TracingFactory {

    /** The instrumentation scope the earlier build's spans came from, kept so queries on it keep working. */
    static final String SCOPE = "org.springframework.boot";

    @Singleton
    @Bean(preDestroy = "close")
    OpenTelemetrySdk openTelemetry(List<SpanProcessor> processors, OtlpEndpoints endpoints,
                                   @Value("${management.tracing.sampling.probability:1.0}") double probability,
                                   @Value("${micronaut.application.name:Excalibase graphql}") String serviceName) {
        SdkTracerProviderBuilder provider = SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(probability)))
                .setResource(Resource.getDefault().merge(
                        Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), serviceName))));
        processors.forEach(provider::addSpanProcessor);
        endpoints.traces().map(TracingFactory::otlpProcessor).ifPresent(provider::addSpanProcessor);
        if (processors.isEmpty() && endpoints.traces().isEmpty()) {
            provider.addSpanProcessor(new IdOnlySpanProcessor());
        }
        return OpenTelemetrySdk.builder().setTracerProvider(provider.build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
    }

    @Singleton
    Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(SCOPE);
    }

    /**
     * The SDK hands out invalid spans when nothing listens to them, which would drop the trace ids from the logs.
     * With no collector this processor listens and keeps nothing.
     */
    private static final class IdOnlySpanProcessor implements SpanProcessor {
        @Override
        public void onStart(Context parentContext, ReadWriteSpan span) {
            // nothing to record: only the ids are wanted
        }

        @Override
        public boolean isStartRequired() {
            return true;
        }

        @Override
        public void onEnd(ReadableSpan span) {
            // spans leave the process only through an exporter
        }

        @Override
        public boolean isEndRequired() {
            return false;
        }
    }

    private static SpanProcessor otlpProcessor(String endpoint) {
        return BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()).build();
    }
}
