package io.github.excalibase.observability.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.github.excalibase.http.RequestThreads;
import jakarta.inject.Singleton;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.ToDoubleFunction;

/**
 * {@code tomcat.threads.config.max}, {@code .current} and {@code .busy}, under the names the Tomcat build used. On
 * virtual threads, the default, there is no ceiling and no pool, so all three are -1 as they were.
 */
@Singleton
class RequestThreadMeters implements MeterBinder {

    private static final String CONNECTOR = "http-netty";
    private static final double NO_POOL = -1;

    private final RequestThreads threads;

    RequestThreadMeters(RequestThreads threads) {
        this.threads = threads;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        register(registry, "tomcat.threads.config.max", ThreadPoolExecutor::getMaximumPoolSize);
        register(registry, "tomcat.threads.current", ThreadPoolExecutor::getPoolSize);
        register(registry, "tomcat.threads.busy", ThreadPoolExecutor::getActiveCount);
    }

    private void register(MeterRegistry registry, String name, ToDoubleFunction<ThreadPoolExecutor> reading) {
        Gauge.builder(name, threads, known -> known.pool() != null ? reading.applyAsDouble(known.pool()) : NO_POOL)
                .tag("name", CONNECTOR).baseUnit("threads").register(registry);
    }
}
