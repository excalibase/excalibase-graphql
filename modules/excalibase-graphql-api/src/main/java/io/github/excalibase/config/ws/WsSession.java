package io.github.excalibase.config.ws;

import java.io.IOException;
import java.util.Map;

/** What the handlers need of a WebSocket session, whatever server carries it. */
public interface WsSession {

    String getId();

    Map<String, Object> getAttributes();

    boolean isOpen();

    /** True while the client is not reading what was sent, so more would only pile up in memory. */
    default boolean isBackedUp() {
        return false;
    }

    /**
     * The one monitor every send, ping and close on this session holds, so two threads never write a frame at the
     * same time. Implementations hand out the same object for the life of the session.
     */
    default Object sendLock() {
        return this;
    }

    /** {@link #sendLock()}, or the session itself for an implementation that has none. */
    static Object lockOf(WsSession session) {
        Object lock = session.sendLock();
        return lock == null ? session : lock;
    }

    void sendMessage(TextMessage message) throws IOException;

    void sendPing() throws IOException;

    void close(CloseStatus status) throws IOException;

    /** A text frame. */
    record TextMessage(String payload) {
        public String getPayload() {
            return payload;
        }
    }

    /** A close code with its reason. */
    record CloseStatus(int code, String reason) {
        public static final CloseStatus NORMAL = new CloseStatus(1000, null);
        public static final CloseStatus POLICY_VIOLATION = new CloseStatus(1008, null);
        public static final CloseStatus SERVICE_OVERLOAD = new CloseStatus(1013, null);

        public CloseStatus withReason(String newReason) {
            return new CloseStatus(code, newReason);
        }
    }
}
