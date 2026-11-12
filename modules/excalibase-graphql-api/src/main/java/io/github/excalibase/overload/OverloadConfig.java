package io.github.excalibase.overload;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every limit is derived from the CPUs the container has, so one image fits any size. */
@Factory
public class OverloadConfig {

    private static final Logger log = LoggerFactory.getLogger(OverloadConfig.class);

    @Context
    @Requires(property = "app.overload.enabled", notEquals = "false")
    TomcatConnectionBound connectionBound(
            @Value("${app.overload.max-connections-per-cpu:256}") int connectionsPerCpu,
            @Value("${app.overload.accept-count-per-cpu:256}") int backlogPerCpu,
            @Value("${app.overload.max-keep-alive-requests:20}") int maxKeepAliveRequests,
            @Value("${app.overload.keep-alive-timeout-ms:5000}") int keepAliveTimeoutMillis) {
        int cpus = Runtime.getRuntime().availableProcessors();
        TomcatConnectionBound bound = new TomcatConnectionBound(cpus, connectionsPerCpu, backlogPerCpu,
                maxKeepAliveRequests, keepAliveTimeoutMillis);
        log.info("Connection bound on the main port: cpus={}, {}", cpus, bound);
        return bound;
    }
}
