package io.github.excalibase.config.ws;

import org.springframework.web.socket.CloseStatus;

/** The close codes a client can see from the engine's WebSocket limits, with stable reasons. */
final class WsClose {

    /** graphql-ws: no connection_init within the allowed time. */
    static final CloseStatus INIT_TIMEOUT = new CloseStatus(4408, "connection_init_timeout");
    /** graphql-ws: a message that needs an initialised connection came first. */
    static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "connection_init_required");
    /** The session's token passed its expiry. */
    static final CloseStatus TOKEN_EXPIRED = new CloseStatus(4401, "token_expired");
    /** The client does not read what is sent to it. */
    static final CloseStatus SLOW_CONSUMER = new CloseStatus(4429, "slow_consumer");
    /** The project's permissions changed: reconnect to subscribe under the new ones. */
    static final CloseStatus PERMISSIONS_CHANGED = new CloseStatus(1012, "permissions_changed");
    /** Too many sockets for this client or this project. */
    static final CloseStatus LIMIT = new CloseStatus(1013, "websocket_limit");

    private WsClose() {
    }
}
