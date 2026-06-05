package io.github.excalibase.config.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.rls.Operation;
import io.github.excalibase.rls.RlsPolicyEnforcer;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
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
 * <p>Subscription key is {@code "{schema|public}_{collection}"}, matching the
 * sink key {@link SubscriptionService} publishes under.
 *
 * <p>Filter matching (v1): equality on a single top-level field. Empty/null
 * filter means "all events for this collection".
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final RlsPolicyEnforcer rlsEnforcer;

    @Value("${app.security.jwt-enabled:false}")
    private boolean jwtEnabled;

    @Value("${app.nats.tenant-in-subject:false}")
    private boolean wsAuthRequired;

    // sessionId -> (subscriptionId -> Disposable)
    private final Map<String, Map<String, Disposable>> sessionSubscriptions = new ConcurrentHashMap<>();
    private final WebSocketHeartbeat heartbeat;

    public RealtimeWebSocketHandler(SubscriptionService subscriptionService,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<JwtService> jwtServiceProvider,
                                    ObjectProvider<RlsPolicyEnforcer> rlsEnforcerProvider,
                                    WebSocketHeartbeat heartbeat) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.jwtService = jwtServiceProvider.getIfAvailable();
        this.rlsEnforcer = rlsEnforcerProvider.getIfAvailable();
        this.heartbeat = heartbeat;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
        heartbeat.register(session);
        log.debug("Realtime WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
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
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        heartbeat.unregister(session);
        Map<String, Disposable> subs = sessionSubscriptions.remove(session.getId());
        if (subs != null) {
            subs.values().forEach(d -> {
                try { d.dispose(); } catch (Exception e) { log.debug("dispose failed", e); }
            });
        }
        log.debug("Realtime WS closed: {} (status {})", session.getId(), status);
    }

    @SuppressWarnings("unchecked")
    private void handleSubscribe(WebSocketSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        String collection = (String) msg.get("collection");
        String schema = (String) msg.getOrDefault("schema", null);
        Map<String, Object> filter = (Map<String, Object>) msg.getOrDefault("filter", Map.of());

        if (id == null || collection == null) {
            sendError(session, id, "subscribe requires id and collection");
            return;
        }

        String key = (schema == null ? "public" : schema) + "_" + collection;

        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) {
            sendError(session, id, "Session not initialized");
            return;
        }

        Disposable existing = sessionSubs.remove(id);
        if (existing != null) existing.dispose();

        String tenantId = (String) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_TENANT_KEY);
        if (jwtEnabled && wsAuthRequired && tenantId == null) {
            sendError(session, id, "Unauthenticated: send connection_init with Authorization first");
            return;
        }
        // Resource the CDC events belong to, matching how policies are keyed
        // (schema-qualified table) so column masking can be applied per subscriber.
        String resource = (schema == null ? "public" : schema) + "." + collection;
        Disposable disposable = subscriptionService.subscribe(tenantId, key)
                .subscribe(event -> dispatchEvent(session, id, filter, resource, event));
        sessionSubs.put(id, disposable);
        log.debug("Realtime subscribe: session={} id={} key={}", session.getId(), id, key);
    }

    private void handleComplete(WebSocketSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) return;
        Disposable disposable = sessionSubs.remove(id);
        if (disposable != null) disposable.dispose();
    }

    /**
     * Optional authentication handshake, mirroring GraphQLWebSocketHandler. If
     * {@link JwtHandshakeInterceptor} already authenticated via HTTP Authorization
     * header, this is a no-op. Otherwise the client may pass the JWT in
     * {@code payload.Authorization}.
     */
    @SuppressWarnings("unchecked")
    private void handleConnectionInit(WebSocketSession session, Map<String, Object> msg) {
        if (!jwtEnabled || session.getAttributes().get(GraphQLWebSocketHandler.SESSION_TENANT_KEY) != null) {
            sendAck(session);
            return;
        }
        Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault("payload", Map.of());
        String token = extractBearerToken(payload);
        if (token != null && jwtService != null) {
            try {
                JwtClaims claims = jwtService.verify(token);
                String tenantId = GraphQLWebSocketHandler.tenantIdFromClaims(claims);
                if (tenantId != null) {
                    session.getAttributes().put(GraphQLWebSocketHandler.SESSION_TENANT_KEY, tenantId);
                    session.getAttributes().put(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY, claims);
                    log.info("Realtime session {} authenticated via connection_init for tenant '{}'",
                            session.getId(), tenantId);
                }
            } catch (JwtVerificationException e) {
                closeWithAuthError(session, "Invalid or expired token");
                return;
            }
        } else if (wsAuthRequired) {
            closeWithAuthError(session, "Missing Authorization token in connection_init payload");
            return;
        }
        sendAck(session);
    }

    @SuppressWarnings("unchecked")
    private String extractBearerToken(Map<String, Object> payload) {
        Object direct = payload.get("Authorization");
        if (direct == null) {
            Object headers = payload.get("headers");
            if (headers instanceof Map<?, ?> headerMap) {
                direct = ((Map<String, Object>) headerMap).get("Authorization");
            }
        }
        if (!(direct instanceof String header) || !header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).trim();
    }

    private void sendAck(WebSocketSession session) {
        sendMessage(session, "{\"type\":\"connection_ack\"}");
    }

    private void closeWithAuthError(WebSocketSession session, String reason) {
        try {
            sendMessage(session, objectMapper.writeValueAsString(Map.of(
                    "type", "connection_error",
                    "payload", Map.of("message", reason))));
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException e) {
            log.warn("Error closing unauthenticated session", e);
        }
    }

    private void dispatchEvent(WebSocketSession session, String subId,
                                Map<String, Object> filter, String resource, CDCEvent event) {
        String op = switch (event.type() == null ? "" : event.type().toUpperCase()) {
            case "INSERT" -> "insert";
            case "UPDATE" -> "update";
            case "DELETE" -> "delete";
            default -> null;
        };
        if (op == null) return;

        JsonNode doc;
        try {
            doc = (event.data() == null || event.data().isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(event.data());
        } catch (Exception e) {
            log.debug("Failed to parse CDC data for sub {}: {}", subId, e.getMessage());
            return;
        }

        // Filter matching runs on the raw row (a masked column must not change
        // which events the subscriber receives), then column-level security
        // masks the payload per subscriber before it leaves the server.
        if (!matchesFilter(doc, filter)) return;
        Object payloadDoc = maskDoc(session, resource, doc);

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "next");
            payload.put("id", subId);
            payload.put("op", op);
            payload.put("doc", payloadDoc);
            sendMessage(session, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize realtime event for sub {}", subId, e);
        }
    }

    /**
     * Applies column-level security to a CDC row for the session's subscriber.
     * Returns the original {@code doc} unchanged when masking can't apply (engine
     * not wired, or an unauthenticated session with no claims); otherwise returns
     * a map with hidden columns dropped and NULL-masked columns nulled.
     */
    private Object maskDoc(WebSocketSession session, String resource, JsonNode doc) {
        JwtClaims claims = (JwtClaims) session.getAttributes().get(GraphQLWebSocketHandler.SESSION_CLAIMS_KEY);
        if (rlsEnforcer == null || claims == null || claims.projectId() == null) {
            return doc;
        }
        Map<String, Object> row = objectMapper.convertValue(doc, new TypeReference<>() {});
        return rlsEnforcer.maskRow(claims.projectId(), resource, claims, Operation.SELECT, row);
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

    private void sendError(WebSocketSession session, String id, String message) {
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

    private void sendMessage(WebSocketSession session, String payload) {
        try {
            // Serialise on the session monitor: CDC events fan out from multiple
            // Reactor threads onto one session, and the heartbeat pings on another
            // — a raw WebSocketSession is not safe for concurrent sends.
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            log.debug("Send failed on session {}: {}", session.getId(), e.getMessage());
        }
    }
}
