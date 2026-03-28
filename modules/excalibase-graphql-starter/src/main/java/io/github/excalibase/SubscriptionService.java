package io.github.excalibase;

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
 * Decoupled from NATS — any event source (NATS, test harness, etc.) can call {@link #publish(CDCEvent)}.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final Map<String, Sinks.Many<CDCEvent>> tableSinks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

    /**
     * Subscribe to CDC events for a specific table.
     * Returns a Flux that emits events as they arrive.
     */
    public Flux<CDCEvent> subscribe(String tableName) {
        return getOrCreateSink(tableName).asFlux()
                .doOnSubscribe(s -> {
                    int count = subscriberCounts
                            .computeIfAbsent(tableName, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    log.info("Client subscribed to table '{}' (count: {})", tableName, count);
                })
                .doOnCancel(() -> {
                    AtomicInteger count = subscriberCounts.get(tableName);
                    if (count != null) {
                        int current = count.decrementAndGet();
                        log.info("Client unsubscribed from table '{}' (count: {})", tableName, current);
                        if (current <= 0) {
                            cleanupSink(tableName);
                        }
                    }
                });
    }

    /**
     * Publish a CDC event to all subscribers of the event's table.
     */
    public void publish(CDCEvent event) {
        if (event.table() == null) {
            return;
        }
        Sinks.Many<CDCEvent> sink = tableSinks.get(event.table());
        if (sink != null) {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                log.warn("Failed to emit CDC event for table '{}': {}", event.table(), result);
                if (result == Sinks.EmitResult.FAIL_TERMINATED) {
                    tableSinks.remove(event.table());
                    Sinks.Many<CDCEvent> newSink = getOrCreateSink(event.table());
                    newSink.tryEmitNext(event);
                }
            }
        }
    }

    private synchronized Sinks.Many<CDCEvent> getOrCreateSink(String tableName) {
        Sinks.Many<CDCEvent> sink = tableSinks.get(tableName);
        if (sink == null) {
            sink = Sinks.many().multicast().onBackpressureBuffer();
            tableSinks.put(tableName, sink);
        } else {
            Boolean terminated = sink.scan(reactor.core.Scannable.Attr.TERMINATED);
            if (terminated == Boolean.TRUE) {
                sink = Sinks.many().multicast().onBackpressureBuffer();
                tableSinks.put(tableName, sink);
            }
        }
        return sink;
    }

    private synchronized void cleanupSink(String tableName) {
        AtomicInteger count = subscriberCounts.get(tableName);
        if (count != null && count.get() <= 0) {
            Sinks.Many<CDCEvent> sink = tableSinks.remove(tableName);
            if (sink != null) {
                sink.tryEmitComplete();
            }
            subscriberCounts.remove(tableName);
        }
    }
}
