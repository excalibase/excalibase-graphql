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

/**
 * Minimal GraphQL over WebSocket handler that supports graphql-transport-ws subset
 * for subscriptions using graphql-java.
 */
@Component
public class GraphQLWebSocketHandler extends TextWebSocketHandler implements SubProtocolCapable {
    private final GraphqlConfig graphqlConfig;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public GraphQLWebSocketHandler(GraphqlConfig graphqlConfig) {
        this.graphqlConfig = graphqlConfig;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Expect client to send {"type":"connection_init"}
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        Map<String, Object> msg = JsonUtil.parseJson(payload);
        String type = (String) msg.get("type");

        if ("connection_init".equals(type)) {
            session.sendMessage(new TextMessage("{\"type\":\"connection_ack\"}"));
            return;
        }

        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
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
                session.sendMessage(new TextMessage("{\"type\":\"error\",\"id\":\"" + id + "\",\"payload\":{\"message\":\"Subscription execution failed\"}}"));
                return;
            }

            AtomicReference<Subscription> subRef = new AtomicReference<>();
            publisher.subscribe(new Subscriber<ExecutionResult>() {
                @Override
                public void onSubscribe(Subscription s) {
                    subRef.set(s);
                    subscriptions.put(id, s);
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
                        session.sendMessage(new TextMessage(json));
                    } catch (IOException e) {
                        onError(e);
                    }
                    subRef.get().request(1);
                }

                @Override
                public void onError(Throwable t) {
                    try {
                        String json = JsonUtil.toJson(Map.of(
                                "type", "error",
                                "id", id,
                                "payload", Map.of("message", t.getMessage())
                        ));
                        session.sendMessage(new TextMessage(json));
                    } catch (IOException ignored) {}
                }

                @Override
                public void onComplete() {
                    try {
                        String json = JsonUtil.toJson(Map.of(
                                "type", "complete",
                                "id", id
                        ));
                        session.sendMessage(new TextMessage(json));
                    } catch (IOException ignored) {}
                }
            });
            return;
        }

        if ("complete".equals(type)) {
            String id = (String) msg.get("id");
            Subscription s = subscriptions.remove(id);
            if (s != null) s.cancel();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        subscriptions.values().forEach(Subscription::cancel);
        subscriptions.clear();
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("graphql-transport-ws");
    }
}


