package io.github.excalibase.config.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.schema.NamingUtils;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.security.JwtClaims;
import io.github.excalibase.security.JwtService;
import io.github.excalibase.security.JwtVerificationException;
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

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;

    @Value("${app.security.jwt-enabled:false}")
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
                                   WebSocketHeartbeat heartbeat) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
        this.jwtService = jwtServiceProvider.getIfAvailable();
        this.heartbeat = heartbeat;
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

    @SuppressWarnings("unchecked")
    private void handleConnectionInit(WebSocketSession session, Map<String, Object> msg) {
        if (jwtEnabled && session.getAttributes().get(SESSION_TENANT_KEY) == null) {
            // JwtHandshakeInterceptor did not authenticate at HTTP upgrade (no Authorization
            // header). Try the connection_init payload next.
            Map<String, Object> payload = (Map<String, Object>) msg.getOrDefault(PAYLOAD, Map.of());
            String token = extractBearerToken(payload);
            if (token != null) {
                if (jwtService == null) {
                    log.error("jwt-enabled=true but JwtService bean is not available");
                    closeWithAuthError(session, "Server misconfigured: JWT enabled but no verifier");
                    return;
                }
                try {
                    JwtClaims claims = jwtService.verify(token);
                    String tenantId = tenantIdFromClaims(claims);
                    if (tenantId == null) {
                        closeWithAuthError(session, "JWT missing projectId claim");
                        return;
                    }
                    session.getAttributes().put(SESSION_TENANT_KEY, tenantId);
                    log.info("WS session {} authenticated via connection_init for tenant '{}'",
                            session.getId(), tenantId);
                } catch (JwtVerificationException e) {
                    closeWithAuthError(session, "Invalid or expired token");
                    return;
                }
            } else if (wsAuthRequired) {
                // Strict mode (tenant-in-subject=true): no token is a hard reject.
                closeWithAuthError(session, "Missing Authorization token in connection_init payload");
                return;
            }
            // Permissive mode + no token: pass through as null-tenant (matches HTTP filter semantics).
        }
        sendMessage(session, "{\"type\":\"connection_ack\"}");
    }

    /**
     * Tenant key used for NATS subject filtering. Returns the opaque {@code projectId}
     * claim ({@code proj_XXXXXXXXXX}) minted by the provisioner — same identifier used
     * for K8s namespace, vault paths, and pgdog database name. Watcher deployments use
     * {@code WATCHER_NATS_SUBJECT_PREFIX=cdc.{projectId}}.
     */
    static String tenantIdFromClaims(JwtClaims claims) {
        if (claims == null) return null;
        return claims.projectId();
    }

    @SuppressWarnings("unchecked")
    private String extractBearerToken(Map<String, Object> payload) {
        // Accept both payload.Authorization and payload.headers.Authorization (Apollo and common conventions)
        Object direct = payload.get("Authorization");
        if (direct == null) {
            Object headers = payload.get("headers");
            if (headers instanceof Map<?, ?> headerMap) {
                direct = ((Map<String, Object>) headerMap).get("Authorization");
            }
        }
        if (!(direct instanceof String header)) return null;
        if (!header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).trim();
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

        String tenantId = (String) session.getAttributes().get(SESSION_TENANT_KEY);
        log.info("Starting subscription '{}' for tenant '{}' table '{}' (field: '{}'), session {}",
                id, tenantId, tableName, fieldName, session.getId());

        // Subscribe to the table's event stream — scoped to the JWT's tenant (null in single-tenant mode)
        Disposable disposable = subscriptionService.subscribe(tenantId, tableName)
                .subscribe(event -> {
                    try {
                        Map<String, Object> changeData = Map.of(
                                "operation", event.type(),
                                "table", event.table(),
                                "data", parseEventData(event.data()),
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

    private Object parseEventData(String data) {
        if (data == null || data.isBlank()) return "";
        try {
            return objectMapper.readValue(data, Object.class);
        } catch (Exception _) {
            return data;
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
