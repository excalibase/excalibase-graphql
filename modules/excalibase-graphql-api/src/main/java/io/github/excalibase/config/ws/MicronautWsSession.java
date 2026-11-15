package io.github.excalibase.config.ws;

import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A Micronaut WebSocket session seen through {@link WsSession}. */
final class MicronautWsSession implements WsSession {

    private static final String KEY = "excalibase.wsSession";
    private static final String OPENED = "excalibase.wsOpened";
    private static final long OPEN_WAIT_SECONDS = 10;

    private final WebSocketSession delegate;
    private final Map<String, Object> attributes;

    MicronautWsSession(WebSocketSession delegate, Map<String, Object> attributes) {
        this.delegate = delegate;
        this.attributes = attributes;
    }

    /** The adapter made for the session at open, or a fresh one with no attributes. */
    static MicronautWsSession of(WebSocketSession session) {
        return session.get(KEY, MicronautWsSession.class)
                .orElseGet(() -> open(session, new ConcurrentHashMap<>()));
    }

    /**
     * The upgrade finishes on a request thread while the client's first frame is read on the event loop, so a
     * frame can reach its handler before the open handler has run. Called when the open handler is done.
     */
    static void markOpened(WebSocketSession session) {
        opened(session).complete(null);
    }

    /** Waits until the open handler has run, so a frame is never handled on a session that is not set up. */
    static void awaitOpened(WebSocketSession session) {
        try {
            opened(session).get(OPEN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("WebSocket session " + session.getId() + " was not opened", failure);
        }
    }

    private static CompletableFuture<Void> opened(WebSocketSession session) {
        synchronized (session) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> opened = session.get(OPENED, CompletableFuture.class)
                    .orElse(null);
            if (opened == null) {
                opened = new CompletableFuture<>();
                session.put(OPENED, opened);
            }
            return opened;
        }
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
    public boolean isBackedUp() {
        return !delegate.isWritable();
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
