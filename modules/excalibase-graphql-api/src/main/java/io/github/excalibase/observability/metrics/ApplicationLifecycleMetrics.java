package io.github.excalibase.observability.metrics;

import io.github.excalibase.ExcalibaseGraphqlApplication;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** How long the JVM took to the end of start-up and to taking traffic, as {@code application.started.time} and {@code .ready.time}. */
@Context
public class ApplicationLifecycleMetrics {

    private static final String MAIN_CLASS = "main.application.class";

    private final MeterRegistry registry;

    ApplicationLifecycleMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    void started(StartupEvent event) {
        register("application.started.time", "Time taken to start the application");
    }

    @EventListener
    void ready(ServerStartupEvent event) {
        register("application.ready.time", "Time taken for the application to be ready to service requests");
    }

    private void register(String name, String description) {
        double seconds = sinceJvmStart().toMillis() / 1000.0;
        TimeGauge.builder(name, seconds, TimeUnit.SECONDS, Double::doubleValue).description(description)
                .tag(MAIN_CLASS, ExcalibaseGraphqlApplication.class.getName()).register(registry);
    }

    private static Duration sinceJvmStart() {
        return Duration.between(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()), Instant.now());
    }
}
