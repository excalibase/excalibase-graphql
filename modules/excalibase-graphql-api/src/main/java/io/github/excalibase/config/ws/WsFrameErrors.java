package io.github.excalibase.config.ws;

import io.micronaut.websocket.WebSocketSession;
import io.netty.handler.codec.http.websocketx.CorruptedWebSocketFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a WebSocket endpoint does with a failure the framework reports. Without an error handler the framework logs
 * every one at ERROR with a stack trace, so a client sending oversized frames fills the error log; here that one
 * case is a single WARN line, and anything else is still an ERROR with its stack.
 */
final class WsFrameErrors {

    private static final Logger log = LoggerFactory.getLogger(WsFrameErrors.class);

    private WsFrameErrors() {
    }

    static void report(WebSocketSession session, Throwable failure) {
        if (failure instanceof CorruptedWebSocketFrameException) {
            log.warn("Closed WebSocket session {}: a frame over {} bytes (oversized)", session.getId(),
                    WsLimits.MAX_FRAME_BYTES);
        } else {
            log.error("Unexpected exception in WebSocket session {}", session.getId(), failure);
        }
    }
}
