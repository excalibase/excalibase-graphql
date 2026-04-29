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

    /**
     * When true, the subject shape is {@code {prefix}.{tenantId}.{schema}.{table}} and the
     * tenantId is parsed out of each event's subject. When false, subjects are the legacy
     * single-tenant shape {@code {prefix}.{schema}.{table}} and events publish with null tenant.
     */
    @Value("${app.nats.tenant-in-subject:false}")
    private boolean tenantInSubject;

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
                String tenantId = tenantInSubject ? parseTenantId(msg.getSubject(), event) : null;
                subscriptionService.publish(tenantId, event);
            }
        } catch (Exception e) {
            log.error("Failed to process NATS CDC message: subject={}", msg.getSubject(), e);
            msg.ack(); // ack to avoid redelivery loop
        }
    }

    /**
     * Extract tenantId from a CDC subject. The subject shape is
     * {@code {prefix}.[tenantTokens...].{schema}.{table}}. Since schema and table are the
     * last two tokens (watcher guarantee), everything between {@code prefix} and those two
     * tokens is the tenant identifier. Returns {@code null} when no tenant segment is present.
     */
    String parseTenantId(String subject, CDCEvent event) {
        if (subject == null || event == null) return null;
        String head = subjectPrefix + ".";
        if (!subject.startsWith(head)) {
            return null;
        }
        String afterHead = subject.substring(head.length());
        String schemaTable = event.schema() + "." + event.table();
        if (!afterHead.endsWith(schemaTable)) {
            return null;
        }
        String middle = afterHead.substring(0, afterHead.length() - schemaTable.length());
        // middle is either "" (no tenant) or "{tenantTokens}."
        if (middle.isEmpty()) return null;
        return middle.endsWith(".") ? middle.substring(0, middle.length() - 1) : middle;
    }

    private boolean isDmlEvent(CDCEvent event) {
        String type = event.type();
        return "INSERT".equals(type) || "UPDATE".equals(type) || "DELETE".equals(type);
    }
}
