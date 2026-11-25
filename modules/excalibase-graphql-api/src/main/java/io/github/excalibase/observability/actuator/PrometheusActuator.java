package io.github.excalibase.observability.actuator;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Read;

/** {@code /actuator/prometheus}: every meter in the Prometheus text format. */
@Endpoint(id = "prometheus", prefix = ActuatorEndpoint.PREFIX, defaultEnabled = true, defaultSensitive = false)
@Requires(condition = ExposedEndpointCondition.class)
@Requires(beans = PrometheusMeterRegistry.class)
public class PrometheusActuator {

    private final PrometheusMeterRegistry registry;

    PrometheusActuator(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @Read(produces = "text/plain; version=0.0.4; charset=utf-8")
    public String scrape() {
        return registry.scrape();
    }
}
