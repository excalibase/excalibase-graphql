package io.github.excalibase.observability.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.inject.Singleton;

/**
 * {@code tomcat.threads.config.max} is -1: requests run on virtual threads, so there is no thread ceiling, which is
 * what the earlier build reported by default. The other Tomcat thread meters have no counterpart here.
 */
@Singleton
class ThreadCeilingMeter implements MeterBinder {

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("tomcat.threads.config.max", () -> -1).tag("name", "http-netty").baseUnit("threads").register(registry);
    }
}
