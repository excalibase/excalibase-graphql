package io.github.excalibase.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.*;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NATS-backed CDC consumer. Only activates when {@code app.nats.enabled=true}.
 * Subscribes to CDC events from excalibase-watcher via JetStream and routes them
 * through {@link SubscriptionService} to WebSocket subscribers.
 */
@Service
public class NatsCDCService {

    private static final Logger log = LoggerFactory.getLogger(NatsCDCService.class);

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final java.util.List<Runnable> schemaReloadCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Value("${app.nats.enabled:false}")
    private boolean natsEnabled;

    @Value("${app.nats.url:nats://localhost:4222}")
    private String natsUrl;

    @Value("${app.nats.stream-name:CDC}")
    private String streamName;

    @Value("${app.nats.subject-prefix:cdc}")
    private String subjectPrefix;

    private Connection natsConnection;
    private JetStreamSubscription subscription;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NatsCDCService(SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Add a callback to be invoked when a DDL event is received (for schema reload).
     * Multiple callbacks are supported (e.g., GraphqlSchemaManager + CollectionSchemaManager).
     */
    public void setSchemaReloadCallback(Runnable callback) {
        this.schemaReloadCallbacks.add(callback);
    }

    @PostConstruct
    public void start() {
        if (!natsEnabled) {
            log.info("NATS CDC service disabled (app.nats.enabled=false)");
            return;
        }
        try {
            Options options = Options.builder()
                    .server(natsUrl)
                    .reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1)
                    .connectionListener((conn, type) ->
                            log.debug("NATS connection event: {}", type))
                    .build();

            natsConnection = Nats.connect(options);
            JetStream js = natsConnection.jetStream();
            Dispatcher dispatcher = natsConnection.createDispatcher();

            String subject = subjectPrefix + ".>";
            ConsumerConfiguration cc = ConsumerConfiguration.builder()
                    .deliverPolicy(DeliverPolicy.New)
                    .build();
            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                    .stream(streamName)
                    .configuration(cc)
                    .build();

            subscription = js.subscribe(subject, dispatcher, this::handleMessage, false, opts);
            running.set(true);
            log.info("NATS CDC service started - subscribing to '{}' on stream '{}'", subject, streamName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while starting NATS CDC service", e);
        } catch (Exception e) {
            log.error("Failed to start NATS CDC service", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (subscription != null) {
            subscription.unsubscribe();
        }
        try {
            if (natsConnection != null) {
                natsConnection.close();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        running.set(false);
        log.info("NATS CDC service stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handleMessage(Message msg) {
        try {
            CDCEvent event = objectMapper.readValue(msg.getData(), CDCEvent.class);
            msg.ack();

            if ("DDL".equals(event.type())) {
                log.info("DDL event received - reloading schema");
                if (!schemaReloadCallbacks.isEmpty()) {
                    schemaReloadCallbacks.forEach(Runnable::run);
                }
                return;
            }

            if (event.table() != null && isDmlEvent(event)) {
                subscriptionService.publish(event);
            }
        } catch (Exception e) {
            log.error("Failed to process NATS CDC message: subject={}", msg.getSubject(), e);
            msg.ack(); // ack to avoid redelivery loop
        }
    }

    private boolean isDmlEvent(CDCEvent event) {
        String type = event.type();
        return "INSERT".equals(type) || "UPDATE".equals(type) || "DELETE".equals(type);
    }
}
