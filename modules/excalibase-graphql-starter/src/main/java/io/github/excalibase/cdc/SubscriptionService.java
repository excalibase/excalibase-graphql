package io.github.excalibase.cdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages per-table Reactor sinks for CDC event routing.
 * Decoupled from NATS — any event source (NATS, test harness, etc.) can call
 * {@link #publish(String, CDCEvent)}.
 *
 * <p>Events are routed by ({@code tenantId}, {@code schema_table}). A {@code null}
 * tenantId means "no tenant scope" and stays isolated from any tenant-scoped
 * subscriber: a null-tenant publish is never delivered to a tenant-scoped
 * subscriber, and vice-versa.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    private static final String NULL_TENANT = "__null__";

    private final Map<String, Sinks.Many<CDCEvent>> tableSinks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

    /**
     * Check if a (tenant, table) pair has active subscribers.
     */
    public boolean hasSubscribers(String tenantId, String tableName) {
        AtomicInteger count = subscriberCounts.get(sinkKey(tenantId, tableName));
        return count != null && count.get() > 0;
    }

    /**
     * Subscribe to CDC events for a (tenant, table) pair.
     */
    public Flux<CDCEvent> subscribe(String tenantId, String tableName) {
        String key = sinkKey(tenantId, tableName);
        return getOrCreateSink(key).asFlux()
                .doOnSubscribe(s -> {
                    int count = subscriberCounts
                            .computeIfAbsent(key, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    log.info("Client subscribed to tenant '{}' table '{}' (count: {})",
                            tenantId, tableName, count);
                })
                .doOnCancel(() -> {
                    AtomicInteger count = subscriberCounts.get(key);
                    if (count != null) {
                        int current = count.decrementAndGet();
                        log.info("Client unsubscribed from tenant '{}' table '{}' (count: {})",
                                tenantId, tableName, current);
                        if (current <= 0) {
                            cleanupSink(key);
                        }
                    }
                });
    }

    /**
     * Publish a CDC event to subscribers scoped to ({@code tenantId}, event.schema_event.table).
     */
    public void publish(String tenantId, CDCEvent event) {
        if (event.table() == null) {
            return;
        }
        String tableKey = (event.schema() != null ? event.schema() + "_" : "") + event.table();
        String key = sinkKey(tenantId, tableKey);
        Sinks.Many<CDCEvent> sink = tableSinks.get(key);
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("Failed to emit CDC event for tenant '{}' table '{}': {}",
                        tenantId, event.table(), result);
                if (result == Sinks.EmitResult.FAIL_TERMINATED) {
                    tableSinks.remove(key);
                    Sinks.Many<CDCEvent> newSink = getOrCreateSink(key);
                    newSink.tryEmitNext(event);
                }
            }
        }
    }

    private static String sinkKey(String tenantId, String tableKey) {
        return (tenantId != null ? tenantId : NULL_TENANT) + "|" + tableKey;
    }

    private synchronized Sinks.Many<CDCEvent> getOrCreateSink(String key) {
        Sinks.Many<CDCEvent> sink = tableSinks.get(key);
        if (sink == null) {
            sink = Sinks.many().multicast().onBackpressureBuffer();
            tableSinks.put(key, sink);
        } else {
            Boolean terminated = sink.scan(reactor.core.Scannable.Attr.TERMINATED);
            if (terminated == Boolean.TRUE) {
                sink = Sinks.many().multicast().onBackpressureBuffer();
                tableSinks.put(key, sink);
            }
        }
        return sink;
    }

    private synchronized void cleanupSink(String key) {
        AtomicInteger count = subscriberCounts.get(key);
        if (count != null && count.get() <= 0) {
            Sinks.Many<CDCEvent> sink = tableSinks.remove(key);
            if (sink != null) {
                sink.tryEmitComplete();
            }
            subscriberCounts.remove(key);
        }
    }
}
