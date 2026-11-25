package io.github.excalibase.config.ws;

import io.micronaut.websocket.WebSocketSession;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a WebSocket endpoint does with a failure the framework reports. Without an error handler the framework logs
 * every one at ERROR with a stack trace, so a client sending oversized or malformed frames fills the error log; here that
 * case is a single WARN line carrying the exception's own message, and anything else is still an ERROR with its stack.
 */
final class WsFrameErrors {

    private static final Logger log = LoggerFactory.getLogger(WsFrameErrors.class);

    private WsFrameErrors() {
    }

    static void report(WebSocketSession session, Throwable failure) {
        if (failure instanceof CorruptedWebSocketFrameException) {
            log.warn("Closed WebSocket session {}: {}", session.getId(), failure.getMessage());
        } else {
            log.error("Unexpected exception in WebSocket session {}", session.getId(), failure);
        }
    }
}
