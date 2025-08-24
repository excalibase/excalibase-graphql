
package io.github.excalibase.postgres.service;

import io.github.excalibase.postgres.constant.PostgresErrorConstant;
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
    
    @Value("${app.cdc.enabled:true}")
    private boolean cdcEnabled;
    
    @Value("${app.cdc.slot-name:cdc_slot}")
    private String slotName;
    
    @Value("${app.cdc.publication-name:cdc_publication}")
    private String publicationName;
    
    @Value("${app.cdc.create-slot-if-not-exists:true}")
    private boolean createSlotIfNotExists;
    
    @Value("${app.cdc.create-publication-if-not-exists:true}")
    private boolean createPublicationIfNotExists;

    private PostgresCDCListener cdcListener;

    // Event streams for each table
    private final Map<String, Sinks.Many<CDCEvent>> tableSinks = new ConcurrentHashMap<>();
    
    // Manual subscriber count tracking for accurate cleanup
    private final Map<String, AtomicInteger> tableSubscriberCounts = new ConcurrentHashMap<>();

    // Health monitoring - use volatile for thread safety during recreation
    private volatile Sinks.Many<String> healthSink = Sinks.many().multicast().onBackpressureBuffer();

    @PostConstruct
    public void startCDC() {
        if (!cdcEnabled) {
            log.info("CDC is disabled in configuration. Skipping CDC initialization.");
            return;
        }
        
        cdcListener = new PostgresCDCListener.Builder()
                .jdbcUrl(jdbcUrl)
                .credentials(username, password)
                .slotName(slotName)
                .publicationName(publicationName)
                .createSlotIfNotExists(createSlotIfNotExists)
                .createPublicationIfNotExists(createPublicationIfNotExists)
                .eventHandler(this::handleCDCEvent)
                .build();

        try {
            cdcListener.start();
            log.info(PostgresErrorConstant.CDC_SERVICE_STARTED);

        } catch (Exception e) {
            log.error(PostgresErrorConstant.CDC_SERVICE_FAILED, e);
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

        log.info(PostgresErrorConstant.CDC_SERVICE_STOPPED);
    }

    /**
     * Get event stream for a specific table
     */
    public Flux<CDCEvent> getTableEventStream(String tableName) {
        return getOrCreateTableSink(tableName).asFlux()
                .doOnSubscribe(s -> {
                    tableSubscriberCounts.computeIfAbsent(tableName, k -> new AtomicInteger(0)).incrementAndGet();
                    log.info(PostgresErrorConstant.TABLE_STREAM_CLIENT_SUBSCRIBED, 
                             tableName, tableSubscriberCounts.get(tableName).get());
                })
                .doOnCancel(() -> {
                    int currentCount = tableSubscriberCounts.get(tableName).decrementAndGet();
                    log.info(PostgresErrorConstant.TABLE_STREAM_CLIENT_UNSUBSCRIBED, 
                             tableName, currentCount);
                    cleanupTableSinkIfNoSubscribers(tableName);
                })
                .doOnComplete(() -> log.warn(PostgresErrorConstant.TABLE_STREAM_COMPLETED, tableName))
                .doOnError(t -> log.error(PostgresErrorConstant.TABLE_STREAM_ERROR, tableName, t));
    }

    /**
     * Handle incoming CDC events and route to appropriate table sinks
     * Package-private for testing
     */
    void handleCDCEvent(CDCEvent event) {
        try {
            log.debug(PostgresErrorConstant.PROCESSING_CDC_EVENT,
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
                            log.warn(PostgresErrorConstant.INVALID_JSON_CDC, tableName, event.getData());
                            return;
                        }
                    } catch (Exception e) {
                        log.warn(PostgresErrorConstant.ERROR_VALIDATING_CDC, tableName, e.getMessage());
                        return;
                    }
                }

                Sinks.EmitResult result = sink.tryEmitNext(event);
                if (result.isFailure()) {
                    log.warn(PostgresErrorConstant.FAILED_EMIT_CDC, tableName, result);

                    // If sink failed due to termination, recreate it
                    if (result == Sinks.EmitResult.FAIL_TERMINATED) {
                        log.info(PostgresErrorConstant.RECREATING_SINK, tableName);
                        sink = getOrCreateTableSink(tableName);
                        // Retry emission once
                        result = sink.tryEmitNext(event);
                        if (result.isFailure()) {
                            log.error(PostgresErrorConstant.FAILED_EMIT_AFTER_RECREATION, tableName, result);
                        } else {
                            log.info(PostgresErrorConstant.SUCCESS_EMIT_AFTER_RECREATION, tableName);
                        }
                    }
                } else {
                    log.debug(PostgresErrorConstant.SUCCESS_EMIT_CDC, tableName);
                }
            }
        } catch (Exception e) {
            log.error(PostgresErrorConstant.UNEXPECTED_ERROR_CDC, e);
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
            log.debug(PostgresErrorConstant.CLEANUP_CHECK, tableName, currentCount);
            
            if (currentCount <= 0) {
                log.info(PostgresErrorConstant.NO_MORE_SUBSCRIBERS, tableName);
                sink.tryEmitComplete();
                tableSinks.remove(tableName);
                tableSubscriberCounts.remove(tableName);
            } else {
                log.debug(PostgresErrorConstant.STILL_HAS_SUBSCRIBERS, tableName, currentCount);
            }
        } else {
            log.debug(PostgresErrorConstant.NO_SINK_FOUND, tableName);
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
            log.debug(PostgresErrorConstant.CREATED_NEW_SINK, tableName);
        } else {
            // Check if existing sink is terminated and recreate if needed
            Boolean isTerminated = sink.scan(reactor.core.Scannable.Attr.TERMINATED);
            if (isTerminated == Boolean.TRUE) {
                log.warn(PostgresErrorConstant.SINK_TERMINATED_RECREATING, tableName);
                sink = Sinks.many().multicast().onBackpressureBuffer();
                tableSinks.put(tableName, sink);
                log.info(PostgresErrorConstant.SUCCESS_RECREATED_SINK, tableName);
            } else {
                log.debug(PostgresErrorConstant.SINK_ACTIVE, tableName, sink.currentSubscriberCount());
            }
        }

        return sink;
    }

    /**
     * Get current CDC listener status
     */
    public boolean isRunning() {
        return cdcEnabled && cdcListener != null;
    }
    
    /**
     * Check if CDC is enabled in configuration
     */
    public boolean isCdcEnabled() {
        return cdcEnabled;
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