package io.github.excalibase.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.CDCEvent;
import io.github.excalibase.NamingUtils;
import io.github.excalibase.SubscriptionService;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GraphQL over WebSocket handler (graphql-transport-ws protocol) for subscriptions.
 * <p>
 * Does NOT use GraphQL-Java execution -- parses the subscription query to extract the
 * table name, subscribes to the SubscriptionService Reactor sink, and forwards CDC
 * events as "next" messages.
 * </p>
 */
@Component
public class GraphQLWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {

    private static final Logger log = LoggerFactory.getLogger(GraphQLWebSocketHandler.class);
    private static final String PAYLOAD = "payload";

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    // sessionId -> (subscriptionId -> Disposable)
    private final Map<String, Map<String, Disposable>> sessionSubscriptions = new ConcurrentHashMap<>();

    public GraphQLWebSocketHandler(SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("WebSocket connection established: {}", session.getId());
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = objectMapper.readValue(
                message.getPayload(), new TypeReference<>() {});
        String type = (String) msg.get("type");

        switch (type) {
            case "connection_init" -> handleConnectionInit(session);
            case "ping" -> sendMessage(session, "{\"type\":\"pong\"}");
            case "subscribe" -> handleSubscribe(session, msg);
            case "complete" -> handleComplete(session, msg);
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WebSocket closed for session {}: {}", session.getId(), status);
        Map<String, Disposable> subs = sessionSubscriptions.remove(session.getId());
        if (subs != null) {
            subs.values().forEach(d -> {
                try {
                    d.dispose();
                } catch (Exception e) {
                    log.warn("Error disposing subscription: ", e);
                }
            });
            log.debug("Disposed {} subscriptions for session {}", subs.size(), session.getId());
        }
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("graphql-transport-ws");
    }

    // -- Private handlers --

    private void handleConnectionInit(WebSocketSession session) {
        sendMessage(session, "{\"type\":\"connection_ack\"}");
    }

    @SuppressWarnings("unchecked")
    private void handleSubscribe(WebSocketSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Object> payloadMap = (Map<String, Object>) msg.get(PAYLOAD);
        String query = (String) payloadMap.get("query");

        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs == null) {
            sendError(session, id, "Session not properly initialized");
            return;
        }

        // Cancel existing subscription with same ID
        Disposable existing = sessionSubs.remove(id);
        if (existing != null) {
            existing.dispose();
        }

        // Extract table name and field name from subscription query
        String[] extracted = extractTableAndFieldFromSubscription(query);
        if (extracted == null) {
            sendError(session, id, "Could not extract table name from subscription query");
            return;
        }
        String tableName = extracted[0];
        String fieldName = extracted[1];

        log.info("Starting subscription '{}' for table '{}' (field: '{}'), session {}",
                id, tableName, fieldName, session.getId());

        // Subscribe to the table's event stream
        Disposable disposable = subscriptionService.subscribe(tableName)
                .subscribe(event -> {
                    try {
                        Map<String, Object> changeData = Map.of(
                                "operation", event.type(),
                                "table", event.table(),
                                "data", event.data() != null ? event.data() : "",
                                "timestamp", event.timestamp()
                        );
                        Map<String, Object> nextMsg = Map.of(
                                "type", "next",
                                "id", id,
                                PAYLOAD, Map.of("data", Map.of(fieldName, changeData))
                        );
                        sendMessage(session, objectMapper.writeValueAsString(nextMsg));
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize CDC event for subscription {}: ", id, e);
                    }
                });

        sessionSubs.put(id, disposable);
    }

    private void handleComplete(WebSocketSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs != null) {
            Disposable d = sessionSubs.remove(id);
            if (d != null) {
                d.dispose();
                log.debug("Cancelled subscription {} for session {}", id, session.getId());
            }
        }
    }

    /**
     * Extract table name (snake_case) and field name from a subscription query.
     * E.g., "subscription { customerChanges { operation table data } }" -> ["customer", "customerChanges"]
     * E.g., "subscription { orderItemsChanges { ... } }" -> ["order_items", "orderItemsChanges"]
     *
     * @return [tableName, fieldName] or null if parsing fails
     */
    String[] extractTableAndFieldFromSubscription(String query) {
        try {
            Document doc = Parser.parse(query);
            List<OperationDefinition> ops = doc.getDefinitionsOfType(OperationDefinition.class);
            if (ops.isEmpty()) {
                return null;
            }
            OperationDefinition op = ops.getFirst();
            for (Selection<?> sel : op.getSelectionSet().getSelections()) {
                if (sel instanceof Field f) {
                    String name = f.getName(); // e.g., "customerChanges"
                    if (name.endsWith("Changes")) {
                        String tablePart = name.substring(0, name.length() - "Changes".length());
                        // camelCase -> snake_case
                        String tableName = NamingUtils.camelToSnakeCase(tablePart);
                        return new String[]{tableName, name};
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse subscription query: {}", query, e);
        }
        return null;
    }

    private void sendError(WebSocketSession session, String id, String message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "error",
                    "id", id,
                    PAYLOAD, Map.of("message", message)
            ));
            sendMessage(session, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize error message: ", e);
        }
    }

    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
            } else {
                log.warn("Attempted to send message to closed session {}", session.getId());
            }
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to session {}: ", session.getId(), e);
        }
    }
}
