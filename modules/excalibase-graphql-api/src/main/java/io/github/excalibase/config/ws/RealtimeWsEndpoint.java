package io.github.excalibase.config.ws;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import org.reactivestreams.Publisher;
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

/** {@code /{projectId}/api/v1/realtime} upgrades: the REST CDC stream. */
@ServerWebSocket(value = "/{projectId}/api/v1/realtime")
public class RealtimeWsEndpoint {

    private final RealtimeWebSocketHandler handler;

    public RealtimeWsEndpoint(RealtimeWebSocketHandler handler) {
        this.handler = handler;
    }

    /**
     * Returns a publisher so Micronaut runs this on the event loop, in order with the frames that follow;
     * a void handler is offloaded and the first message can overtake it.
     */
    @OnOpen
    public Publisher<Void> onOpen(WebSocketSession session, HttpRequest<?> request) {
        handler.afterConnectionEstablished(MicronautWsSession.open(session, attributes(request)));
        return Publishers.empty();
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
