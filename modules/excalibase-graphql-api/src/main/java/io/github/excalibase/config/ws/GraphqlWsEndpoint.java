package io.github.excalibase.config.ws;

import io.micronaut.http.HttpRequest;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@code /{projectId}/graphql} upgrades: GraphQL over WebSocket (graphql-transport-ws) CDC subscriptions. */
@ServerWebSocket(value = "/{projectId}/graphql", subprotocols = "graphql-transport-ws")
public class GraphqlWsEndpoint {

    private final GraphQLWebSocketHandler handler;

    public GraphqlWsEndpoint(GraphQLWebSocketHandler handler) {
        this.handler = handler;
    }

    @OnOpen
    public void onOpen(WebSocketSession session, HttpRequest<?> request) {
        handler.afterConnectionEstablished(MicronautWsSession.open(session, attributes(request)));
    }

    @OnMessage
    @ExecuteOn(TaskExecutors.BLOCKING)
    public void onMessage(String message, WebSocketSession session) throws Exception {
        handler.onMessage(MicronautWsSession.of(session), message);
    }

    @OnClose
    public void onClose(WebSocketSession session, CloseReason reason) {
        handler.afterConnectionClosed(MicronautWsSession.of(session),
                new WsSession.CloseStatus(reason.getCode(), reason.getReason()));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attributes(HttpRequest<?> request) {
        Object attributes = request.getAttribute(WsHandshakeFilter.ATTRIBUTES).orElse(null);
        return attributes instanceof Map<?, ?> map ? (Map<String, Object>) map : new ConcurrentHashMap<>();
    }
}
