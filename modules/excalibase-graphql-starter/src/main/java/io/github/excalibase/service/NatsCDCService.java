package io.github.excalibase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.model.CDCEvent;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NATS-backed CDC service that subscribes to excalibase-watcher events via JetStream.
 * <p>
 * Replaces the old embedded PostgresCDCListener approach. Now excalibase-graphql is a pure
 * NATS consumer — the watcher owns the replication slot / binlog connection.
 * </p>
 * <p>
 * DML events (INSERT/UPDATE/DELETE) are routed to per-table Reactor Sinks for GraphQL subscriptions.
 * DDL events trigger schema cache invalidation so the GraphQL schema auto-refreshes.
 * </p>
 */
@Service
public class NatsCDCService {

    private static final Logger log = LoggerFactory.getLogger(NatsCDCService.class);

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final ServiceLookup serviceLookup;
    private final FullSchemaService fullSchemaService;

    private Connection natsConnection;
    private Dispatcher dispatcher;
    private JetStreamSubscription dmlSubscription;

    private final Map<String, Sinks.Many<CDCEvent>> tableSinks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tableSubscriberCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NatsCDCService(AppConfig appConfig, ObjectMapper objectMapper,
                          ServiceLookup serviceLookup, FullSchemaService fullSchemaService) {
        this.appConfig = appConfig;
        this.objectMapper = objectMapper;
        this.serviceLookup = serviceLookup;
        this.fullSchemaService = fullSchemaService;
    }

    @PostConstruct
    public void start() {
        AppConfig.NatsConfig nats = appConfig.getNats();
        if (!nats.isEnabled()) {
            log.info("NATS CDC subscription disabled (app.nats.enabled=false)");
            return;
        }

        try {
            Options options = Options.builder()
                    .server(nats.getUrl())
                    .reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1)
                    .connectionListener((conn, type) ->
                            log.debug("NATS connection event: {}", type))
                    .build();

            natsConnection = Nats.connect(options);
            JetStream js = natsConnection.jetStream();

            // Create dispatcher for async message handling
            dispatcher = natsConnection.createDispatcher();

            // Subscribe to all CDC events: cdc.>
            String subject = nats.getSubjectPrefix() + ".>";
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                    .deliverPolicy(DeliverPolicy.New)
                    .build();
            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                    .stream(nats.getStreamName())
                    .configuration(cc)
                    .build();

            dmlSubscription = js.subscribe(subject, dispatcher, this::handleNatsMessage, false, opts);

            running.set(true);
            log.info("NATS CDC service started — subscribing to '{}' on stream '{}'",
                    subject, nats.getStreamName());

        } catch (Exception e) {
            log.error("Failed to start NATS CDC service", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (dmlSubscription != null) {
            dmlSubscription.unsubscribe();
        }
        try {
            if (natsConnection != null) {
                natsConnection.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        tableSinks.values().forEach(Sinks.Many::tryEmitComplete);
        tableSubscriberCounts.clear();
        running.set(false);
        log.info("NATS CDC service stopped");
    }

    /**
     * Get event stream for a specific table. Used by subscription resolvers.
     */
    public Flux<CDCEvent> getTableEventStream(String tableName) {
        return getOrCreateTableSink(tableName).asFlux()
                .doOnSubscribe(s -> {
                    tableSubscriberCounts
                            .computeIfAbsent(tableName, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    log.info("Client subscribed to table '{}' (count: {})",
                            tableName, tableSubscriberCounts.get(tableName).get());
                })
                .doOnCancel(() -> {
                    AtomicInteger count = tableSubscriberCounts.get(tableName);
                    if (count != null) {
                        int current = count.decrementAndGet();
                        log.info("Client unsubscribed from table '{}' (count: {})", tableName, current);
                        if (current <= 0) {
                            cleanupTableSink(tableName);
                        }
                    }
                });
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isEnabled() {
        return appConfig.getNats().isEnabled();
    }

    // ── Private ─────────────────────────────────────────────────────────────────

    private void handleNatsMessage(Message msg) {
        try {
            CDCEvent event = objectMapper.readValue(msg.getData(), CDCEvent.class);
            msg.ack();

            if (event.getType() == CDCEvent.Type.DDL) {
                handleDdlEvent(event);
                return;
            }

            if (event.getTable() != null && isDmlEvent(event)) {
                routeToTableSink(event);
            }
        } catch (Exception e) {
            log.error("Failed to process NATS CDC message: subject={}", msg.getSubject(), e);
            msg.ack(); // ack to avoid redelivery loop on bad messages
        }
    }

    private boolean isDmlEvent(CDCEvent event) {
        return event.getType() == CDCEvent.Type.INSERT
                || event.getType() == CDCEvent.Type.UPDATE
                || event.getType() == CDCEvent.Type.DELETE;
    }

    private void routeToTableSink(CDCEvent event) {
        String tableName = event.getTable();
        Sinks.Many<CDCEvent> sink = getOrCreateTableSink(tableName);
        Sinks.EmitResult result = sink.tryEmitNext(event);

        if (result.isFailure()) {
            log.warn("Failed to emit CDC event for table '{}': {}", tableName, result);
            if (result == Sinks.EmitResult.FAIL_TERMINATED) {
                tableSinks.remove(tableName);
                sink = getOrCreateTableSink(tableName);
                sink.tryEmitNext(event);
            }
        }
    }

    private void handleDdlEvent(CDCEvent event) {
        log.info("DDL event received: schema='{}', data='{}'",
                event.getSchema(),
                event.getData() != null ? event.getData().substring(0, Math.min(200, event.getData().length())) : "null");

        try {
            // Invalidate schema reflector cache
            IDatabaseSchemaReflector reflector = serviceLookup.forBean(
                    IDatabaseSchemaReflector.class,
                    appConfig.getDatabaseType().getName());

            if (event.getSchema() != null) {
                reflector.clearCache(event.getSchema());
            } else {
                reflector.clearCache();
            }

            // Invalidate the full schema cache (used by GraphqlConfig)
            fullSchemaService.clearCache();

            log.info("Schema cache invalidated due to DDL event");
        } catch (Exception e) {
            log.error("Failed to invalidate schema cache on DDL event", e);
        }
    }

    private synchronized Sinks.Many<CDCEvent> getOrCreateTableSink(String tableName) {
        Sinks.Many<CDCEvent> sink = tableSinks.get(tableName);

        if (sink == null) {
            sink = Sinks.many().multicast().onBackpressureBuffer();
            tableSinks.put(tableName, sink);
        } else {
            Boolean isTerminated = sink.scan(reactor.core.Scannable.Attr.TERMINATED);
            if (isTerminated == Boolean.TRUE) {
                sink = Sinks.many().multicast().onBackpressureBuffer();
                tableSinks.put(tableName, sink);
            }
        }

        return sink;
    }

    private synchronized void cleanupTableSink(String tableName) {
        AtomicInteger count = tableSubscriberCounts.get(tableName);
        if (count != null && count.get() <= 0) {
            Sinks.Many<CDCEvent> sink = tableSinks.remove(tableName);
            if (sink != null) {
                sink.tryEmitComplete();
            }
            tableSubscriberCounts.remove(tableName);
        }
    }
}
