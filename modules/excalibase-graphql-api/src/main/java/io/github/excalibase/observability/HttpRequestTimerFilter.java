package io.github.excalibase.observability;

import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Records {@code http.server.requests} with a plain timer: method, status, outcome, uri (the matched pattern,
 * never the raw path), exception, error, and the tenant, org and project labels. Inert at level {@code off}.
 */
@ServerFilter(Filter.MATCH_ALL_PATTERN)
public class HttpRequestTimerFilter implements Ordered {

    static final String METRIC = "http.server.requests";
    private static final String START = "excalibase.timer.start";
    private static final String ACTIVE = "excalibase.timer.active";

    private record TimerKey(HttpRequestFacts facts) {
    }

    private final MeterRegistry registry;
    private final ObservabilityLevel level;
    private final ConcurrentHashMap<TimerKey, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongTaskTimer> activeTimers = new ConcurrentHashMap<>();

    private final int managementPort;
    private final boolean percentileHistogram;

    public HttpRequestTimerFilter(MeterRegistry registry, ObservabilityLevel level, int managementPort) {
        this(registry, level, managementPort, false);
    }

    /**
     * {@code percentileHistogram} publishes {@code http.server.requests} buckets, which the platform's p99 latency
     * alert reads; the earlier build took it from {@code management.metrics.distribution.percentiles-histogram.http.server.requests}.
     */
    @Inject
    public HttpRequestTimerFilter(MeterRegistry registry, ObservabilityLevel level,
                                  @Value("${endpoints.all.port:-1}") int managementPort,
                                  @Value("${management.metrics.distribution.percentiles-histogram.http.server.requests:false}")
                                  boolean percentileHistogram) {
        this.registry = registry;
        this.level = level;
        this.managementPort = managementPort;
        this.percentileHistogram = percentileHistogram;
    }

    @Override
    public int getOrder() {
        return -1000;
    }

    @RequestFilter
    public void start(HttpRequest<?> request) {
        if (!level.timesRequests() || onManagementPort(request)) {
            return;
        }
        request.getAttributes().put(ACTIVE, activeTimer(HttpRequestFacts.method(request)).start());
        request.getAttributes().put(START, System.nanoTime());
    }

    @ResponseFilter
    public void end(HttpRequest<?> request, HttpResponse<?> response, @Nullable Throwable thrown) {
        Object started = request.getAttribute(START).orElse(null);
        if (!(started instanceof Long startNanos)) {
            return;
        }
        if (request.getAttribute(ACTIVE).orElse(null) instanceof LongTaskTimer.Sample sample) {
            sample.stop();
        }
        HttpRequestFacts facts = HttpRequestFacts.of(request, response.code(), thrown);
        timers.computeIfAbsent(new TimerKey(facts), this::newTimer).record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /** Requests to the actuator port are not part of the served traffic and are never timed. */
    private boolean onManagementPort(HttpRequest<?> request) {
        InetSocketAddress server = request.getServerAddress();
        return server != null && server.getPort() == managementPort;
    }

    private Timer newTimer(TimerKey key) {
        HttpRequestFacts facts = key.facts();
        return Timer.builder(METRIC).tags(Tags.of(
                Tag.of("method", facts.method()),
                Tag.of("status", Integer.toString(facts.status())),
                Tag.of("uri", facts.uri()),
                Tag.of("outcome", facts.outcome()),
                Tag.of("exception", facts.exception()),
                Tag.of("error", facts.exception()),
                Tag.of("tenant.id", facts.tenant().tenantId()),
                Tag.of("org.slug", facts.tenant().orgSlug()),
                Tag.of("project.name", facts.tenant().projectName()),
                Tag.of("org.name", facts.tenant().orgName())))
                .publishPercentileHistogram(percentileHistogram)
                .register(registry);
    }

    private LongTaskTimer activeTimer(String method) {
        String none = HttpRequestFacts.NONE;
        return activeTimers.computeIfAbsent(method, known -> LongTaskTimer.builder(METRIC + ".active").tags(Tags.of(
                Tag.of("exception", none), Tag.of("method", known), Tag.of("org.name", none),
                Tag.of("org.slug", none), Tag.of("outcome", "SUCCESS"), Tag.of("project.name", none),
                Tag.of("status", "200"), Tag.of("tenant.id", none), Tag.of("uri", "UNKNOWN"))).register(registry));
    }
}
