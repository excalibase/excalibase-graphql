
package io.github.excalibase.postgres.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CDCService {

    private static final Logger log = LoggerFactory.getLogger(CDCService.class);

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    private PostgresCDCListener cdcListener;

    // Event streams for each table
    private final Map<String, Sinks.Many<CDCEvent>> tableSinks = new ConcurrentHashMap<>();
    
    // Manual subscriber count tracking for accurate cleanup
    private final Map<String, AtomicInteger> tableSubscriberCounts = new ConcurrentHashMap<>();

    // Health monitoring - use volatile for thread safety during recreation
    private volatile Sinks.Many<String> healthSink = Sinks.many().multicast().onBackpressureBuffer();

    @PostConstruct
    public void startCDC() {
        cdcListener = new PostgresCDCListener.Builder()
                .jdbcUrl(jdbcUrl)
                .credentials(username, password)
                .slotName("cdc_slot")
                .publicationName("cdc_publication")
                .eventHandler(this::handleCDCEvent)
                .build();

        try {
            cdcListener.start();
            log.info("CDC Service started successfully");

        } catch (Exception e) {
            log.error("Failed to start CDC Service", e);
        }
    }

    @PreDestroy
    public void stopCDC() {
        if (cdcListener != null) {
            cdcListener.stop();
        }

        // Complete all sinks
        tableSinks.values().forEach(Sinks.Many::tryEmitComplete);
        healthSink.tryEmitComplete();
        
        // Clean up subscriber counts
        tableSubscriberCounts.clear();

        log.info("CDC Service stopped");
    }

    /**
     * Get event stream for a specific table
     */
    public Flux<CDCEvent> getTableEventStream(String tableName) {
        return getOrCreateTableSink(tableName).asFlux()
                .doOnSubscribe(s -> {
                    tableSubscriberCounts.computeIfAbsent(tableName, k -> new AtomicInteger(0)).incrementAndGet();
                    log.info("📡 Table stream: Client subscribed to table events: {} (count: {})", 
                             tableName, tableSubscriberCounts.get(tableName).get());
                })
                .doOnCancel(() -> {
                    int currentCount = tableSubscriberCounts.get(tableName).decrementAndGet();
                    log.info("📡 Table stream: Client unsubscribed from table events: {} (count: {})", 
                             tableName, currentCount);
                    cleanupTableSinkIfNoSubscribers(tableName);
                })
                .doOnComplete(() -> log.warn("📡 Table stream: Stream completed unexpectedly for table: {}", tableName))
                .doOnError(t -> log.error("📡 Table stream: Error occurred while streaming table events for {}", tableName, t));
    }

    /**
     * Handle incoming CDC events and route to appropriate table sinks
     * Package-private for testing
     */
    void handleCDCEvent(CDCEvent event) {
        try {
            log.debug("Processing CDC event: type={}, table={}, data={}",
                    event.getType(), event.getTable(),
                    event.getData() != null ? event.getData().substring(0, Math.min(100, event.getData().length())) : "null");

            if (event.getTable() != null &&
                    (event.getType() == CDCEvent.Type.INSERT ||
                            event.getType() == CDCEvent.Type.UPDATE ||
                            event.getType() == CDCEvent.Type.DELETE)) {

                String tableName = event.getTable();
                Sinks.Many<CDCEvent> sink = getOrCreateTableSink(tableName);

                // Validate event data before emitting
                if (event.getData() != null && !event.getData().trim().isEmpty()) {
                    try {
                        // Quick JSON validation
                        if (!event.getData().startsWith("{") || !event.getData().endsWith("}")) {
                            log.warn("Invalid JSON format for CDC event, table {}: {}", tableName, event.getData());
                            return;
                        }
                    } catch (Exception e) {
                        log.warn("Error validating CDC event data for table {}: {}", tableName, e.getMessage());
                        return;
                    }
                }

                Sinks.EmitResult result = sink.tryEmitNext(event);
                if (result.isFailure()) {
                    log.warn("Failed to emit CDC event for table {}: {}", tableName, result);

                    // If sink failed due to termination, recreate it
                    if (result == Sinks.EmitResult.FAIL_TERMINATED) {
                        log.info("Recreating terminated sink for table: {}", tableName);
                        sink = getOrCreateTableSink(tableName);
                        // Retry emission once
                        result = sink.tryEmitNext(event);
                        if (result.isFailure()) {
                            log.error("Failed to emit CDC event after sink recreation for table {}: {}", tableName, result);
                        } else {
                            log.info("Successfully emitted CDC event after sink recreation for table: {}", tableName);
                        }
                    }
                } else {
                    log.debug("Successfully emitted CDC event for table: {}", tableName);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error handling CDC event: ", e);
        }
    }

    /**
     * Clean up table sink if no active subscribers remain
     */
    private synchronized void cleanupTableSinkIfNoSubscribers(String tableName) {
        AtomicInteger subscriberCount = tableSubscriberCounts.get(tableName);
        Sinks.Many<CDCEvent> sink = tableSinks.get(tableName);
        
        if (subscriberCount != null && sink != null) {
            int currentCount = subscriberCount.get();
            log.debug("📡 Table stream: Cleanup check for table {}, current subscribers: {}", tableName, currentCount);
            
            if (currentCount <= 0) {
                log.info("📡 Table stream: No more subscribers for table {}, removing sink", tableName);
                sink.tryEmitComplete();
                tableSinks.remove(tableName);
                tableSubscriberCounts.remove(tableName);
            } else {
                log.debug("📡 Table stream: Table {} still has {} subscribers, keeping sink", tableName, currentCount);
            }
        } else {
            log.debug("📡 Table stream: No sink or subscriber count found for table {} during cleanup", tableName);
        }
    }

    /**
     * Get or create a sink for the specified table, recreating if terminated
     */
    private synchronized Sinks.Many<CDCEvent> getOrCreateTableSink(String tableName) {
        Sinks.Many<CDCEvent> sink = tableSinks.get(tableName);

        if (sink == null) {
            // Create new sink for this table
            sink = Sinks.many().multicast().onBackpressureBuffer();
            tableSinks.put(tableName, sink);
            log.debug("Created new CDC sink for table: {}", tableName);
        } else {
            // Check if existing sink is terminated and recreate if needed
            Boolean isTerminated = sink.scan(reactor.core.Scannable.Attr.TERMINATED);
            if (isTerminated == Boolean.TRUE) {
                log.warn("Table sink for {} is terminated, recreating it", tableName);
                sink = Sinks.many().multicast().onBackpressureBuffer();
                tableSinks.put(tableName, sink);
                log.info("Successfully recreated table sink for: {}", tableName);
            } else {
                log.debug("Table sink for {} is active, current subscriber count: {}", tableName, sink.currentSubscriberCount());
            }
        }

        return sink;
    }

    /**
     * Get current CDC listener status
     */
    public boolean isRunning() {
        return cdcListener != null;
    }

    /**
     * Get number of active table subscriptions
     */
    public int getActiveSubscriptionCount() {
        return tableSubscriberCounts.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }
    
    /**
     * Get number of active table subscriptions for a specific table
     */
    public int getActiveSubscriptionCount(String tableName) {
        AtomicInteger count = tableSubscriberCounts.get(tableName);
        return count != null ? count.get() : 0;
    }
}