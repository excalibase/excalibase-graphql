package io.github.excalibase.observability.tracing;

import io.micrometer.observation.ObservationRegistry;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Singleton;

/** Adds the span handler to the observation registry, whichever configuration creates it. */
@Singleton
class GraphqlSpanRegistration implements BeanCreatedEventListener<ObservationRegistry> {

    private final Tracer tracer;

    GraphqlSpanRegistration(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public ObservationRegistry onCreated(BeanCreatedEvent<ObservationRegistry> event) {
        ObservationRegistry registry = event.getBean();
        registry.observationConfig().observationHandler(new GraphqlSpanHandler(tracer));
        return registry;
    }
}
