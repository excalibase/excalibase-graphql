package io.github.excalibase.config.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.AccessPlans;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.Principal;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
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
    static final String SESSION_TENANT_KEY = "excalibase.tenantId";
    /** Verified JWT claims for the session — used by realtime column masking. */
    static final String SESSION_CLAIMS_KEY = "excalibase.jwtClaims";
    /** Project from the URL path — authoritative for RLS (mirrors the HTTP filter). */
    static final String SESSION_PROJECT_KEY = "excalibase.projectId";
    /** The {@link Principal} the session runs as, resolved at the handshake and at connection_init. */
    static final String SESSION_PRINCIPAL_KEY = "excalibase.principal";

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final ObjectReader changeReader;
    private final JwtService jwtService;
    private final AccessPlans accessPlans;

    @Value("${app.security.jwt-enabled:true}")
    private boolean jwtEnabled;

    /**
     * When true, the WS handler REQUIRES a valid JWT on every connection and
     * rejects unauthenticated {@code connection_init}. Gated on the same flag
     * that enables tenant-in-subject parsing, because multi-tenant routing is
     * meaningless without an authenticated tenant claim. When false, auth is
     * verified if present and passes through otherwise — matching the HTTP
     * {@code JwtAuthFilter}'s permissive semantics for legacy single-tenant stacks.
     */
    @Value("${app.nats.tenant-in-subject:false}")
    private boolean wsAuthRequired;

    // sessionId -> (subscriptionId -> Disposable)
    private final Map<String, Map<String, Disposable>> sessionSubscriptions = new ConcurrentHashMap<>();
    private final WebSocketHeartbeat heartbeat;

    public GraphQLWebSocketHandler(SubscriptionService subscriptionService,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<JwtService> jwtServiceProvider,
                                   WebSocketHeartbeat heartbeat,
                                   ObjectProvider<AccessPlans> accessPlansProvider) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.changeReader = RealtimeGate.changeReader(objectMapper);
        this.jwtService = jwtServiceProvider.getIfAvailable();
        this.heartbeat = heartbeat;
        this.accessPlans = accessPlansProvider.getIfAvailable();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("WebSocket connection established: {}", session.getId());
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
        heartbeat.register(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg = objectMapper.readValue(
                message.getPayload(), new TypeReference<>() {});
        String type = (String) msg.get("type");

        switch (type) {
            case "connection_init" -> handleConnectionInit(session, msg);
            case "ping" -> sendMessage(session, "{\"type\":\"pong\"}");
            case "subscribe" -> handleSubscribe(session, msg);
            case "complete" -> handleComplete(session, msg);
            default -> log.warn("Unknown message type: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WebSocket closed for session {}: {}", session.getId(), status);
        heartbeat.unregister(session);
        Map<String, Disposable> subs = sessionSubscriptions.remove(session.getId());
        if (subs != null) {
            subs.values().forEach(disposable -> {
                try {
                    disposable.dispose();
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

    /**
     * Resolves who the session runs as before acknowledging it; see {@link WsSessionAuth}.
     * With JWT disabled there is no authentication and no principal.
     */
    @SuppressWarnings("unchecked")
    private void handleConnectionInit(WebSocketSession session, Map<String, Object> msg) {
        if (jwtEnabled) {
            Map<String, Object> payload = msg.get(PAYLOAD) instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            try {
                WsSessionAuth.onConnectionInit(jwtService, wsAuthRequired, payload, session.getAttributes());
            } catch (WsSessionAuth.Failure failure) {
                closeWithAuthError(session, failure.getMessage());
                return;
            }
            log.info("WS session {} runs as role '{}' for tenant '{}'", session.getId(),
                    principalOf(session).role(), session.getAttributes().get(SESSION_TENANT_KEY));
        }
        sendMessage(session, "{\"type\":\"connection_ack\"}");
    }

    static Principal principalOf(WebSocketSession session) {
        return (Principal) session.getAttributes().get(SESSION_PRINCIPAL_KEY);
    }

    private void closeWithAuthError(WebSocketSession session, String reason) {
        try {
            sendMessage(session, objectMapper.writeValueAsString(Map.of(
                    "type", "connection_error",
                    PAYLOAD, Map.of("message", reason)
            )));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize connection_error", e);
        }
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException e) {
            log.warn("Error closing unauthenticated session", e);
        }
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
        if (extracted.length == 0) {
            sendError(session, id, "Could not extract table name from subscription query");
            return;
        }
        String tableName = extracted[0];
        String fieldName = extracted[1];

        RealtimeGate.Opening opening = RealtimeGate.open(accessPlans, session, tableName);
        if (opening.delivery() == null) {
            sendError(session, id, opening.refusal());
            if (opening.unavailable()) {
                closeUnavailable(session);
            }
            return;
        }
        RealtimeGate.Delivery delivery = opening.delivery();

        String tenantId = (String) session.getAttributes().get(SESSION_TENANT_KEY);
        log.info("Starting subscription '{}' for tenant '{}' table '{}' (field: '{}'), session {}",
                id, tenantId, tableName, fieldName, session.getId());

        // Subscribe to the table's event stream — scoped to the JWT's tenant (null in single-tenant mode)
        Disposable disposable = subscriptionService.subscribe(tenantId, tableName)
                .subscribe(event -> forward(session, id, fieldName, delivery, event));

        sessionSubs.put(id, disposable);
    }

    /** Sends one change, only as far as the subscriber's role may read it. */
    private void forward(WebSocketSession session, String id, String fieldName, RealtimeGate.Delivery delivery,
                         CDCEvent event) {
        Optional<Object> data = delivery.render(event, parseEventData(event.data()));
        if (data.isEmpty()) {
            return;
        }
        Map<String, Object> changeData = Map.of(
                "operation", event.type(),
                "table", event.table(),
                "data", data.get(),
                "timestamp", event.timestamp()
        );
        Map<String, Object> nextMsg = Map.of(
                "type", "next",
                "id", id,
                PAYLOAD, Map.of("data", Map.of(fieldName, changeData))
        );
        try {
            sendMessage(session, objectMapper.writeValueAsString(nextMsg));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize CDC event for subscription {}: ", id, e);
        }
    }

    private Object parseEventData(String data) {
        if (data == null || data.isBlank()) return "";
        try {
            return changeReader.readValue(data);
        } catch (Exception _) {
            return data;
        }
    }

    /** The permissions could not be read: the session is closed like any other request is refused. */
    private void closeUnavailable(WebSocketSession session) {
        try {
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason(PermissionsUnavailableException.CODE));
        } catch (IOException e) {
            log.warn("Error closing session {}", session.getId(), e);
        }
    }

    private void handleComplete(WebSocketSession session, Map<String, Object> msg) {
        String id = (String) msg.get("id");
        Map<String, Disposable> sessionSubs = sessionSubscriptions.get(session.getId());
        if (sessionSubs != null) {
            Disposable disposable = sessionSubs.remove(id);
            if (disposable != null) {
                disposable.dispose();
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
                return new String[0];
            }
            OperationDefinition op = ops.getFirst();
            for (Selection<?> sel : op.getSelectionSet().getSelections()) {
                if (sel instanceof Field field) {
                    String name = field.getName(); // e.g., "customerChanges"
                    if (name.endsWith("Changes")) {
                        String tablePart = name.substring(0, name.length() - "Changes".length());
                        // camelCase -> snake_case (e.g., "testSchemaCustomer" -> "test_schema.customer")
                        String snakeName = NamingUtils.camelToSnakeCase(tablePart);
                        // Convert underscore-joined schema_table to dot-separated schema.table
                        // by checking if it matches a compound key pattern (contains underscore that represents schema separator)
                        // Simple approach: use the snake_case name as-is (becomes sink key)
                        return new String[]{snakeName, name};
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse subscription query: {}", query, e);
        }
        return new String[0];
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
            // Serialise on the session monitor: subscription results fan out from
            // multiple Reactor threads onto one session, and the heartbeat pings on
            // another — a raw WebSocketSession is not safe for concurrent sends.
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                } else {
                    log.warn("Attempted to send message to closed session {}", session.getId());
                }
            }
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to session {}: ", session.getId(), e);
        }
    }
}
