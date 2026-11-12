package io.github.excalibase.config.ws;

import java.io.IOException;
import java.util.Map;

/** What the handlers need of a WebSocket session, whatever server carries it. */
public interface WsSession {

    String getId();

    Map<String, Object> getAttributes();

    boolean isOpen();

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
        public static final CloseStatus POLICY_VIOLATION = new CloseStatus(1008, null);
        public static final CloseStatus SERVICE_OVERLOAD = new CloseStatus(1013, null);

        public CloseStatus withReason(String newReason) {
            return new CloseStatus(code, newReason);
        }
    }
}
