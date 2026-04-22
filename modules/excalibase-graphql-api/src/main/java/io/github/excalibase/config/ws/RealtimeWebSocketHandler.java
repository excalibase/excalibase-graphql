package io.github.excalibase.config.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified realtime WebSocket endpoint for REST tables and NoSQL collections.
 * Lightweight JSON protocol — no GraphQL parsing needed.
 *
 * <p>Protocol:
 * <pre>
 * client → {"type":"subscribe","id":"s1","source":"rest"|"nosql","collection":"...","filter":{...},"schema":"..."}
 * server → {"type":"next","id":"s1","op":"insert"|"update"|"delete","doc":{...}}
 * client → {"type":"complete","id":"s1"}
 * server → {"type":"error","id":"s1","message":"..."}
 * </pre>
 *
 * <p>Subscription key mapping:
 * <ul>
 *   <li>{@code source=="nosql"} → key {@code "nosql_{collection}"}</li>
 *   <li>{@code source=="rest"}  → key {@code "{schema|public}_{collection}"}</li>
 * </ul>
 *
 * <p>Filter matching (v1): equality on a single top-level field. Empty/null
 * filter means "all events for this collection".
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    // sessionId -> (subscriptionId -> Disposable)
    private final Map<String, Map<String, Disposable>> sessionSubscriptions = new ConcurrentHashMap<>();

    public RealtimeWebSocketHandler(SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
        log.debug("Realtime WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = objectMapper.readValue(
                message.getPayload(), new TypeReference<>() {});
        String type = (String) msg.get("type");
        if (type == null) return;

        switch (type) {
            case "subscribe" -> handleSubscribe(session, msg);
            case "complete" -> handleComplete(session, msg);
            default -> log.debug("Unknown realtime message type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
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
        String source = (String) msg.get("source");
        String collection = (String) msg.get("collection");
        String schema = (String) msg.getOrDefault("schema", null);
        Map<String, Object> filter = (Map<String, Object>) msg.getOrDefault("filter", Map.of());

        if (id == null || source == null || collection == null) {
            sendError(session, id, "subscribe requires id, source, collection");
            return;
        }

        String key = switch (source) {
            case "nosql" -> "nosql_" + collection;
            case "rest" -> (schema == null ? "public" : schema) + "_" + collection;
            default -> null;
        };
        if (key == null) {
            sendError(session, id, "source must be 'rest' or 'nosql'");
            return;
        }

        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) {
            sendError(session, id, "Session not initialized");
            return;
        }

        Disposable existing = sessionSubs.remove(id);
        if (existing != null) existing.dispose();

        Disposable disposable = subscriptionService.subscribe(key)
                .subscribe(event -> dispatchEvent(session, id, filter, event));
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

    private void dispatchEvent(WebSocketSession session, String subId,
                                Map<String, Object> filter, CDCEvent event) {
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

        if (!matchesFilter(doc, filter)) return;

        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "next");
            payload.put("id", subId);
            payload.put("op", op);
            payload.put("doc", doc);
            sendMessage(session, objectMapper.writeValueAsString(payload));
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
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.debug("Send failed on session {}: {}", session.getId(), e.getMessage());
        }
    }
}
