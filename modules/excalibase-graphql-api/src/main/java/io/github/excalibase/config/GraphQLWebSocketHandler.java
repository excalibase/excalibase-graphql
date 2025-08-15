package io.github.excalibase.config;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal GraphQL over WebSocket handler that supports graphql-transport-ws subset
 * for subscriptions using graphql-java.
 */
@Component
public class GraphQLWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private static final Logger log = LoggerFactory.getLogger(GraphQLWebSocketHandler.class);
    
    private final GraphqlConfig graphqlConfig;
    
    // Session-specific subscription storage: sessionId -> (subscriptionId -> Subscription)
    private final Map<String, Map<String, Subscription>> sessionSubscriptions = new ConcurrentHashMap<>();

    public GraphQLWebSocketHandler(GraphqlConfig graphqlConfig) {
        this.graphqlConfig = graphqlConfig;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.debug("WebSocket connection established: {}", session.getId());
        sessionSubscriptions.put(session.getId(), new ConcurrentHashMap<>());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        Map<String, Object> msg = JsonUtil.parseJson(payload);
        String type = (String) msg.get("type");

        if ("connection_init".equals(type)) {
            log.debug("Connection init received for session: {}", session.getId());
            sendMessage(session, "{\"type\":\"connection_ack\"}");
            return;
        }

        if ("ping".equals(type)) {
            sendMessage(session, "{\"type\":\"pong\"}");
            return;
        }

        if ("subscribe".equals(type)) {
            String id = (String) msg.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = (Map<String, Object>) msg.get("payload");
            String query = (String) payloadMap.get("query");
            String operationName = (String) payloadMap.get("operationName");
            @SuppressWarnings("unchecked")
            Map<String, Object> variables = (Map<String, Object>) payloadMap.getOrDefault("variables", Map.of());

            log.info("Starting subscription {} for session {}: {}", id, session.getId(), query);
            log.info("Subscription operation name: {}", operationName);
            log.info("Subscription variables: {}", variables);

            // Get session-specific subscriptions map
            Map<String, Subscription> sessionSubs = sessionSubscriptions.get(session.getId());
            if (sessionSubs == null) {
                sendMessage(session, JsonUtil.toJson(Map.of(
                    "type", "error",
                    "id", id,
                    "payload", Map.of("message", "Session not properly initialized")
                )));
                return;
            }

            // Check if subscription with this ID already exists and cancel it first
            Subscription existingSub = sessionSubs.get(id);
            if (existingSub != null) {
                log.debug("Cancelling existing subscription {} for session {} before creating new one", id, session.getId());
                existingSub.cancel();
                sessionSubs.remove(id);
            }

            GraphQL graphQL = graphqlConfig.graphQL();
            ExecutionInput.Builder builder = ExecutionInput.newExecutionInput()
                    .query(query)
                    .variables(variables);
            if (operationName != null && !operationName.isBlank()) {
                builder.operationName(operationName);
            }
            ExecutionResult result = graphQL.execute(builder.build());

            @SuppressWarnings("unchecked")
            Publisher<ExecutionResult> publisher = (Publisher<ExecutionResult>) result.getData();
            if (publisher == null) {
                // Send error
                sendMessage(session, JsonUtil.toJson(Map.of(
                    "type", "error",
                    "id", id,
                    "payload", Map.of("message", "Subscription execution failed")
                )));
                return;
            }

            AtomicReference<Subscription> subRef = new AtomicReference<>();
            publisher.subscribe(new Subscriber<ExecutionResult>() {

                @Override
                public void onSubscribe(Subscription s) {
                    subRef.set(s);
                    sessionSubs.put(id, s); // Store in session-specific map
                    log.info("Subscription {} started for session {}", id, session.getId());
                    s.request(1);
                }

                @Override
                public void onNext(ExecutionResult er) {
                    try {
                        Object data = er.getData();
                        String json = JsonUtil.toJson(Map.of(
                                "type", "next",
                                "id", id,
                                "payload", Map.of("data", data)
                        ));
                        sendMessage(session, json);
                        log.info("Sent subscription data {} to session {}", id, session.getId());
                    } catch (Exception e) {
                        log.error("Error sending subscription data: ", e);
                        onError(e);
                        return;
                    }
                    
                    // Request next item if subscription is still active
                    Subscription currentSub = subRef.get();
                    if (currentSub != null && sessionSubs.containsKey(id)) {
                        currentSub.request(1);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Subscription {} error for session {}: ", id, session.getId(), t);
                    try {
                        String json = JsonUtil.toJson(Map.of(
                                "type", "error",
                                "id", id,
                                "payload", Map.of("message", t.getMessage())
                        ));
                        sendMessage(session, json);
                    } catch (Exception e) {
                        log.error("Failed to send error message: ", e);
                    }
                    // Remove subscription on error only if it's still the same subscription
                    Subscription currentSub = sessionSubs.get(id);
                    if (currentSub == subRef.get()) {
                        sessionSubs.remove(id);
                    }
                }

                @Override
                public void onComplete() {
                    log.debug("Subscription {} completed for session {}", id, session.getId());
                    try {
                        String json = JsonUtil.toJson(Map.of(
                                "type", "complete",
                                "id", id
                        ));
                        sendMessage(session, json);
                    } catch (Exception e) {
                        log.error("Failed to send complete message: ", e);
                    }
                    // Remove subscription on completion only if it's still the same subscription
                    Subscription currentSub = sessionSubs.get(id);
                    if (currentSub == subRef.get()) {
                        sessionSubs.remove(id);
                    }
                }
            });
            return;
        }

        if ("complete".equals(type)) {
            String id = (String) msg.get("id");
            Map<String, Subscription> sessionSubs = sessionSubscriptions.get(session.getId());
            if (sessionSubs != null) {
                Subscription s = sessionSubs.remove(id);
                if (s != null) {
                    s.cancel();
                    log.debug("Cancelled subscription {} for session {}", id, session.getId());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.debug("WebSocket connection closed for session {}: {}", session.getId(), status);
        
        // Cancel and remove only this session's subscriptions
        Map<String, Subscription> sessionSubs = sessionSubscriptions.remove(session.getId());
        if (sessionSubs != null) {
            sessionSubs.values().forEach(subscription -> {
                try {
                    subscription.cancel();
                } catch (Exception e) {
                    log.warn("Error cancelling subscription: ", e);
                }
            });
            log.debug("Cancelled {} subscriptions for session {}", sessionSubs.size(), session.getId());
        }
    }
    
    /**
     * Safely send message to WebSocket session with error handling
     */
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

    @Override
    public List<String> getSubProtocols() {
        return List.of("graphql-transport-ws");
    }
}


