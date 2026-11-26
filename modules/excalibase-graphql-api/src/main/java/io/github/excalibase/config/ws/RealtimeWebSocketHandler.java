package io.github.excalibase.config.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micronaut.context.annotation.Value;
import io.github.excalibase.config.JwtEnabledSetting;
import jakarta.inject.Inject;
import io.micronaut.core.annotation.Nullable;
import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import jakarta.inject.Singleton;
import reactor.core.Disposable;

import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realtime WebSocket endpoint for REST tables. Lightweight JSON protocol — no
 * GraphQL parsing needed.
 *
 * <p>Protocol:
 * <pre>
 * client → {"type":"subscribe","id":"s1","collection":"...","filter":{...},"schema":"..."}
 * server → {"type":"next","id":"s1","op":"insert"|"update"|"delete","doc":{...}}
 * client → {"type":"complete","id":"s1"}
 * server → {"type":"error","id":"s1","message":"..."}
 * </pre>
 *
 * <p>Subscription key is {@code "{schema|public}.{collection}"}, matching the
 * sink key {@link SubscriptionService} publishes under.
 *
 * <p>Filter matching (v1): equality on a single top-level field. Empty/null
 * filter means "all events for this collection".
 */
@Singleton
public class RealtimeWebSocketHandler extends TextWsHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final ObjectReader changeReader;
    private final ObjectMapper changeWriter;
    private final JwtService jwtService;
    private final AccessPlans accessPlans;

    private boolean jwtEnabled;

    /** The parsed setting, the same answer the authentication filter and the schema manager use. */
    @Inject
    void jwtEnabled(JwtEnabledSetting setting) {
        this.jwtEnabled = setting.enabled();
    }

    @Value("${app.nats.tenant-in-subject:false}")
    private boolean wsAuthRequired;

    /** Changes arrive over NATS; without it a subscription is refused, never accepted to deliver nothing. */
    @Value("${app.nats.enabled:false}")
    private boolean natsEnabled;

    /** For tests that build the handler the way the container does, then turn realtime on. */
    void natsEnabled(boolean enabled) {
        this.natsEnabled = enabled;
    }

    // sessionId -> (subscriptionId -> Disposable)
    private final Map<String, Map<String, Disposable>> sessionSubscriptions = new ConcurrentHashMap<>();
    private final WebSocketHeartbeat heartbeat;
    private final RealtimeMetering metering;
    private final WsRuntime runtime;

    /** For tests of one handler: no caps in the way, delivery on the publishing thread. */
    RealtimeWebSocketHandler(SubscriptionService subscriptionService, ObjectMapper objectMapper,
                             @Nullable JwtService jwtService, WebSocketHeartbeat heartbeat,
                             @Nullable AccessPlans accessPlans, @Nullable RequestMetrics requestMetrics) {
        this(subscriptionService, objectMapper, jwtService, heartbeat, accessPlans, requestMetrics,
                WsRuntime.inline());
        this.natsEnabled = true;
    }

    @Inject
    public RealtimeWebSocketHandler(SubscriptionService subscriptionService,
                                    ObjectMapper objectMapper,
                                    @Nullable JwtService jwtService,
                                    WebSocketHeartbeat heartbeat,
                                    @Nullable AccessPlans accessPlans,
                                    @Nullable RequestMetrics requestMetrics,
                                    WsRuntime runtime) {
        this.runtime = runtime;
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.changeReader = RealtimeGate.changeReader(objectMapper);
        this.changeWriter = RealtimeGate.changeWriter(objectMapper);
        this.jwtService = jwtService;
        this.heartbeat = heartbeat;
        this.accessPlans = accessPlans;
        this.metering = new RealtimeMetering(requestMetrics);
    }

    @Override
    public void afterConnectionEstablished(WsSession session) {
        if (!runtime.admit(session)) {
            runtime.close(session, WsClose.LIMIT);
            return;
        }
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
        heartbeat.register(session);
        metering.opened(session);
        runtime.expectInit(session);
        runtime.closeAtExpiry(session);
        log.debug("Realtime WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WsSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = objectMapper.readValue(
                message.getPayload(), new TypeReference<>() {});
        String type = (String) msg.get("type");
        if (type == null) return;

        switch (type) {
            case "connection_init" -> handleConnectionInit(session, msg);
            case "subscribe" -> handleSubscribe(session, msg);
            case "complete" -> handleComplete(session, msg);
            default -> log.debug("Unknown realtime message type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WsSession session, CloseStatus status) {
        heartbeat.unregister(session);
        metering.closed(session);
        runtime.release(session);
        Map<String, Disposable> subs = sessionSubscriptions.remove(session.getId());
        if (subs != null) {
            subs.values().forEach(d -> {
                try { d.dispose(); } catch (Exception e) { log.debug("dispose failed", e); }
            });
        }
        log.debug("Realtime WS closed: {} (status {})", session.getId(), status);
    }

    @SuppressWarnings("unchecked")
    private void handleSubscribe(WsSession session, Map<String, Object> msg) {
        if (!runtime.isInitialised(session)) {
            runtime.close(session, WsClose.UNAUTHORIZED);
            return;
        }
        String id = (String) msg.get("id");
        String collection = (String) msg.get("collection");
        String schema = (String) msg.getOrDefault("schema", null);
        Map<String, Object> filter = (Map<String, Object>) msg.getOrDefault("filter", Map.of());

        if (id == null || collection == null) {
            sendError(session, id, "subscribe requires id and collection");
            return;
        }

        String resource = (schema == null ? "public" : schema) + "." + collection;

        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) {
            sendError(session, id, "Session not initialized");
            return;
        }

        Disposable existing = sessionSubs.remove(id);
        if (existing != null) existing.dispose();

        String tenantId = RealtimeGate.sinkTenant(session, wsAuthRequired);
        if (unauthenticated(session, id)) {
            return;
        }
        // A collection the role cannot select is refused like one that does not exist.
        long generation = runtime.permissionGeneration(session);
        RealtimeGate.Delivery delivery = openDelivery(session, id, resource, collection);
        if (delivery == null || !runtime.openedUnder(session, delivery.permissionsVersion(), generation)) {
            return;
        }
        if (overSubscriptionCap(session, id, sessionSubs.size())) {
            return;
        }
        Disposable disposable = startDelivery(session, id, filter, delivery, tenantId, resource);
        sessionSubs.put(id, disposable);
        // A close that ran during the subscribe has already emptied and unregistered the map: this one is
        // nobody's, so it is disposed here, which also gives its project slot back.
        if (sessionSubscriptions.get(session.getId()) != sessionSubs) {
            disposable.dispose();
        }
        log.debug("Realtime subscribe: session={} id={} key={}", session.getId(), id, resource);
    }

    private boolean unauthenticated(WsSession session, String id) {
        boolean hasToken = session.getAttributes().get(GraphQLWebSocketHandler.SESSION_TENANT_KEY) != null;
        if (jwtEnabled && wsAuthRequired && !hasToken) {
            sendError(session, id, "Unauthenticated: send connection_init with Authorization first");
            return true;
        }
        return false;
    }

    /** The plan for the collection, or {@code null} once the refusal has been sent. */
    private RealtimeGate.Delivery openDelivery(WsSession session, String id, String resource, String collection) {
        RealtimeGate.Opening opening = RealtimeGate.open(accessPlans, session, resource, natsEnabled);
        if (opening.delivery() != null) {
            return opening.delivery();
        }
        boolean named = opening.unavailable() || opening.refusal().startsWith(RealtimeGate.REALTIME_UNAVAILABLE);
        sendError(session, id, named ? opening.refusal() : "Unknown collection: " + collection);
        if (opening.unavailable()) {
            closeUnavailable(session);
        }
        return null;
    }

    private boolean overSubscriptionCap(WsSession session, String id, int sessionCount) {
        if (runtime.atSessionCap(sessionCount)) {
            sendError(session, id, "Too many subscriptions on this connection");
            return true;
        }
        if (!runtime.reserveSubscription(session)) {
            sendError(session, id, "Too many subscriptions for this project");
            return true;
        }
        return false;
    }

    private Disposable startDelivery(WsSession session, String id, Map<String, Object> filter,
                                     RealtimeGate.Delivery delivery, String tenantId, String resource) {
        try {
            SessionOutbox outbox = runtime.outbox(session);
            return runtime.counted(session, subscriptionService.subscribe(tenantId, resource)
                    .subscribe(event -> outbox.submit(() -> dispatchEvent(session, id, filter, delivery, event))));
        } catch (RuntimeException failure) {
            runtime.cancelReservation(session);
            throw failure;
        }
    }

    private void handleComplete(WsSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) return;
        Disposable disposable = sessionSubs.remove(id);
        if (disposable != null) disposable.dispose();
    }

    /**
     * Resolves who the session runs as before acknowledging it, exactly as the GraphQL
     * handler does; see {@link WsSessionAuth}. With JWT disabled there is no principal.
     */
    @SuppressWarnings("unchecked")
    private void handleConnectionInit(WsSession session, Map<String, Object> msg) {
        if (jwtEnabled) {
            Map<String, Object> payload = msg.get("payload") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            try {
                WsSessionAuth.onConnectionInit(jwtService, wsAuthRequired, payload, session.getAttributes());
            } catch (WsSessionAuth.Failure failure) {
                closeWithAuthError(session, failure.getMessage());
                return;
            }
            log.info("Realtime session {} runs as role '{}' for tenant '{}'", session.getId(),
                    GraphQLWebSocketHandler.principalOf(session).role(),
                    session.getAttributes().get(GraphQLWebSocketHandler.SESSION_TENANT_KEY));
        }
        runtime.initialised(session);
        runtime.closeAtExpiry(session);
        sendAck(session);
    }

    private void sendAck(WsSession session) {
        sendMessage(session, "{\"type\":\"connection_ack\"}");
    }

    private void closeWithAuthError(WsSession session, String reason) {
        try {
            sendMessage(session, objectMapper.writeValueAsString(Map.of(
                    "type", "connection_error",
                    "payload", Map.of("message", reason))));
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException e) {
            log.warn("Error closing unauthenticated session", e);
        }
    }

    private void closeUnavailable(WsSession session) {
        try {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason(PermissionsUnavailableException.CODE));
        } catch (IOException e) {
            log.warn("Error closing session {}", session.getId(), e);
        }
    }

    private void dispatchEvent(WsSession session, String subId,
                                Map<String, Object> filter, RealtimeGate.Delivery delivery, CDCEvent event) {
        if (!delivery.isFor(event)) return;
        String op = switch (event.type() == null ? "" : event.type().toUpperCase()) {
            case "INSERT" -> "insert";
            case "UPDATE" -> "update";
            case "DELETE" -> "delete";
            default -> null;
        };
        if (op == null) return;

        Object doc;
        try {
            doc = (event.data() == null || event.data().isBlank())
                    ? Map.of()
                    : changeReader.readValue(event.data());
        } catch (Exception e) {
            log.debug("Failed to parse CDC data for sub {}: {}", subId, e.getMessage());
            return;
        }

        // The role's permissions decide what of the change is theirs first; the subscriber's own filter
        // then matches only what they may see, so a column they cannot read cannot steer delivery.
        Object visible = delivery.render(event, doc).orElse(null);
        if (visible == null) return;
        JsonNode payloadDoc = changeWriter.valueToTree(visible);
        if (!matchesFilter(payloadDoc, filter)) return;

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "next");
            payload.put("id", subId);
            payload.put("op", op);
            payload.put("doc", payloadDoc);
            if (sendMessage(session, changeWriter.writeValueAsString(payload))) {
                metering.delivered(session);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize realtime event for sub {}", subId, e);
        }
    }

    private boolean matchesFilter(JsonNode doc, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return true;
        for (var entry : filter.entrySet()) {
            JsonNode value = doc.get(entry.getKey());
            if (value == null || value.isNull()) return false;
            if (!value.asText().equals(String.valueOf(entry.getValue()))) return false;
        }
        return true;
    }

    private void sendError(WsSession session, String id, String message) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "error");
            payload.put("id", id);
            payload.put("message", message);
            sendMessage(session, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize realtime error", e);
        }
    }

    /** True when the message went out. */
    private boolean sendMessage(WsSession session, String payload) {
        try {
            // Serialise on the session send lock: CDC events fan out from multiple
            // Reactor threads onto one session, and the heartbeat pings on another
            // — a raw WsSession is not safe for concurrent sends.
            synchronized (WsSession.lockOf(session)) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            log.debug("Send failed on session {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }
}
