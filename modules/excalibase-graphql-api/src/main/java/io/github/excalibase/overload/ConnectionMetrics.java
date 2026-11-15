package io.github.excalibase.overload;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

/** The bound and the open connections as metrics, under the names the Tomcat build used. */
final class ConnectionMetrics {

    private static final String CONNECTOR = "http-netty";

    private ConnectionMetrics() {
    }

    static void register(MeterRegistry registry, ConnectionGate gate, ConnectionBound bound) {
        Gauge.builder("tomcat.connections.config.max", bound, ConnectionBound::maxConnections)
                .tag("name", CONNECTOR).baseUnit("connections").register(registry);
        Gauge.builder("tomcat.connections.current", gate, ConnectionGate::open)
                .tag("name", CONNECTOR).baseUnit("connections").register(registry);
        Gauge.builder("tomcat.connections.keepalive.current", gate.idleConnections(), AtomicInteger::get)
                .tag("name", CONNECTOR).baseUnit("connections").register(registry);
    }
}
