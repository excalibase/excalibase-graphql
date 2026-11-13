package io.github.excalibase.overload;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** With the bound switched off the server's own limit is what applies: the 8192 the earlier build reported. */
@Singleton
@Requires(property = "app.overload.enabled", value = "false")
class ServerDefaultConnectionMeter implements MeterBinder {

    static final double SERVER_DEFAULT_MAX_CONNECTIONS = 8192;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("tomcat.connections.config.max", () -> SERVER_DEFAULT_MAX_CONNECTIONS)
                .tag("name", "http-netty").baseUnit("connections").register(registry);
    }
}
