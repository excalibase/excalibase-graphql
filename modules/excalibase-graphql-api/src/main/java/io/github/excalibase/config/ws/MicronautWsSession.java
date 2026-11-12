package io.github.excalibase.config.ws;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;

import java.util.Map;

/** A Micronaut WebSocket session seen through {@link WsSession}. */
final class MicronautWsSession implements WsSession {

    private static final String KEY = "excalibase.wsSession";

    private final WebSocketSession delegate;
    private final Map<String, Object> attributes;

    MicronautWsSession(WebSocketSession delegate, Map<String, Object> attributes) {
        this.delegate = delegate;
        this.attributes = attributes;
    }

    /** The adapter made for the session at open, or a fresh one with no attributes. */
    static MicronautWsSession of(WebSocketSession session) {
        return session.get(KEY, MicronautWsSession.class)
                .orElseGet(() -> open(session, new java.util.concurrent.ConcurrentHashMap<>()));
    }

    static MicronautWsSession open(WebSocketSession session, Map<String, Object> attributes) {
        MicronautWsSession adapter = new MicronautWsSession(session, attributes);
        session.put(KEY, adapter);
        return adapter;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void sendMessage(TextMessage message) {
        delegate.sendAsync(message.getPayload());
    }

    @Override
    public void sendPing() {
        delegate.sendPingAsync(new byte[0]);
    }

    @Override
    public void close(CloseStatus status) {
        delegate.close(new CloseReason(status.code(), status.reason() == null ? "" : status.reason()));
    }
}
