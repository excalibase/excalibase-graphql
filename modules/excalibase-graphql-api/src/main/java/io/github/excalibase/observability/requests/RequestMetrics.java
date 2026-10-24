package io.github.excalibase.observability.requests;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-project request, connection and realtime-message counts for the request charts.
 * Kept in its own Prometheus registry so the public actuator endpoints gain no
 * per-project series; served only on the internal metrics port.
 */
public final class RequestMetrics {

    /** Set by the GraphQL controller when its 2xx answer carries an {@code errors} array. */
    public static final String GRAPHQL_ERRORS_ATTR = "excalibase.requestMetrics.graphqlErrors";

    private static final String PROJECT_TAG = "project";
    private static final String INSTANCE_TAG = "instance";
    // Fixed so provisioning can merge bucket counts across pods.
    private static final Duration[] BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)
    };

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final int maxProjects;
    private final Set<String> admittedProjects = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> openConnections = new ConcurrentHashMap<>();
    private final Counter dropped;
    private final String instanceId = UUID.randomUUID().toString();

    public RequestMetrics(int maxProjects) {
        this.maxProjects = maxProjects;
        this.dropped = Counter.builder("excalibase.request.metrics.dropped")
                .description("Requests and realtime events not recorded because of the project cap")
                .register(registry);
        // Prometheus forces an *_info series to 1, so the start time is its own series.
        Gauge.builder("excalibase.request.metrics.info", () -> 1)
                .description("This recorder's instance; it changes whenever counters reset")
                .tag(INSTANCE_TAG, instanceId)
                .register(registry);
        long startedEpochSeconds = Instant.now().getEpochSecond();
        Gauge.builder("excalibase.request.metrics.start.time", () -> startedEpochSeconds)
                .description("When this recorder started, in epoch seconds")
                .baseUnit("seconds")
                .tag(INSTANCE_TAG, instanceId)
                .register(registry);
    }

    public String instanceId() {
        return instanceId;
    }

    public void recordRequest(String projectId, RequestFamily family, RequestOutcome outcome, Duration duration) {
        if (!admit(projectId)) {
            return;
        }
        Timer.builder("excalibase.requests")
                .tag(PROJECT_TAG, projectId)
                .tag("family", family.tag())
                .tag("outcome", outcome.tag())
                .serviceLevelObjectives(BUCKETS)
                .publishPercentileHistogram(false)
                .register(registry)
                .record(duration);
    }

    /** True when the connection was counted; only a counted connection may later be closed. */
    public boolean connectionOpened(String projectId) {
        if (!admit(projectId)) {
            return false;
        }
        openConnections.computeIfAbsent(projectId, this::registerConnectionGauge).incrementAndGet();
        Counter.builder("excalibase.realtime.connections.opened").tag(PROJECT_TAG, projectId)
                .register(registry).increment();
        return true;
    }

    public void connectionClosed(String projectId) {
        AtomicInteger open = openConnections.get(projectId);
        if (open != null) {
            open.updateAndGet(count -> Math.max(0, count - 1));
        }
    }

    public void messageDelivered(String projectId) {
        if (admit(projectId)) {
            Counter.builder("excalibase.realtime.messages").tag(PROJECT_TAG, projectId)
                    .register(registry).increment();
        }
    }

    /** Prometheus text exposition format 0.0.4. */
    public String scrape() {
        return registry.scrape();
    }

    private AtomicInteger registerConnectionGauge(String projectId) {
        AtomicInteger open = new AtomicInteger();
        Gauge.builder("excalibase.realtime.connections", open, AtomicInteger::get)
                .tag(PROJECT_TAG, projectId)
                .register(registry);
        return open;
    }

    private boolean admit(String projectId) {
        if (admittedProjects.contains(projectId)) {
            return true;
        }
        synchronized (admittedProjects) {
            if (admittedProjects.contains(projectId)) {
                return true;
            }
            if (admittedProjects.size() >= maxProjects) {
                dropped.increment();
                return false;
            }
            admittedProjects.add(projectId);
            return true;
        }
    }
}
