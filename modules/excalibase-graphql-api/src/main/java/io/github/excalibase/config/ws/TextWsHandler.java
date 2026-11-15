package io.github.excalibase.config.ws;

import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;

import java.util.List;

/** A handler of text-frame sessions: opened, a message, closed. */
public abstract class TextWsHandler {

    public abstract void afterConnectionEstablished(WsSession session);

    protected abstract void handleTextMessage(WsSession session, TextMessage message) throws Exception;

    public abstract void afterConnectionClosed(WsSession session, CloseStatus status);

    public List<String> getSubProtocols() {
        return List.of();
    }

    /** Entry for the server adapter. */
    public final void onMessage(WsSession session, String text) throws Exception {
        handleTextMessage(session, new TextMessage(text));
    }
}
