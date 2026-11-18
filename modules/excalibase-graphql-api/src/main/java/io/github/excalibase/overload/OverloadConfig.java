package io.github.excalibase.overload;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.channel.ChannelOption;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every limit is derived from the CPUs the container has, so one image fits any size. */
@Factory
@Requires(property = "app.overload.enabled", notEquals = "false")
public class OverloadConfig {

    private static final Logger log = LoggerFactory.getLogger(OverloadConfig.class);

    @Context
    ConnectionBound connectionBound(
            @Value("${app.overload.max-connections-per-cpu:256}") int connectionsPerCpu,
            @Value("${app.overload.accept-count-per-cpu:256}") int backlogPerCpu,
            @Value("${app.overload.max-keep-alive-requests:20}") int maxKeepAliveRequests,
            @Value("${app.overload.keep-alive-timeout-ms:5000}") int keepAliveTimeoutMillis) {
        int cpus = Runtime.getRuntime().availableProcessors();
        ConnectionBound bound = new ConnectionBound(cpus, connectionsPerCpu, backlogPerCpu,
                maxKeepAliveRequests, keepAliveTimeoutMillis);
        log.info("Connection bound on the main port: cpus={}, {}", cpus, bound);
        return bound;
    }

    @Singleton
    ConnectionGate connectionGate(ConnectionBound bound) {
        return new ConnectionGate(bound.maxConnections());
    }

    /** Puts the bound on every connection of the main port. */
    @Singleton
    @Requires(property = "app.overload.enabled", notEquals = "false")
    static class BoundRegistration implements BeanCreatedEventListener<NettyServerCustomizer.Registry> {

        private final ConnectionBoundCustomizer customizer;

        BoundRegistration(ConnectionGate gate, ConnectionBound bound,
                @Value("${server.tomcat.connection-timeout:20000}") long firstRequestMillis,
                @Value("${endpoints.all.port:-1}") int managementPort,
                @Value("${excalibase.console.enabled:false}") String console,
                @Value("${excalibase.admin.port:10001}") int adminPort) {
            this.customizer = new ConnectionBoundCustomizer(gate, bound, firstRequestMillis, Set.of(managementPort),
                    "true".equalsIgnoreCase(console) ? adminPort : -1);
        }

        @Override
        public NettyServerCustomizer.Registry onCreated(BeanCreatedEvent<NettyServerCustomizer.Registry> event) {
            event.getBean().register(customizer);
            return event.getBean();
        }
    }

    @Singleton
    MeterBinder connectionMetrics(ConnectionGate gate, ConnectionBound bound) {
        return registry -> ConnectionMetrics.register(registry, gate, bound);
    }

    /** The accept backlog follows the CPUs too: excess clients wait in the kernel queue. */
    @Singleton
    @Requires(property = "app.overload.enabled", notEquals = "false")
    static class AcceptBacklog implements BeanCreatedEventListener<NettyHttpServerConfiguration> {

        private final ConnectionBound bound;

        AcceptBacklog(ConnectionBound bound) {
            this.bound = bound;
        }

        @Override
        public NettyHttpServerConfiguration onCreated(BeanCreatedEvent<NettyHttpServerConfiguration> event) {
            NettyHttpServerConfiguration configuration = event.getBean();
            Map<ChannelOption, Object> options = new HashMap<>(configuration.getOptions());
            options.put(ChannelOption.SO_BACKLOG, bound.acceptBacklog());
            configuration.setOptions(options);
            return configuration;
        }
    }
}
