package io.github.excalibase.config.ws;

import io.github.excalibase.config.ws.WsSession.CloseStatus;
import io.github.excalibase.config.ws.WsSession.TextMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketHeartbeatTest {

    private final WebSocketHeartbeat heartbeat = new WebSocketHeartbeat(0);

    @Test
    @DisplayName("pingAll sends a PING frame to every open registered session")
    void pingAll_pingsOpenSessions() throws Exception {
        WsSession open = mock(WsSession.class);
        when(open.isOpen()).thenReturn(true);
        when(open.getId()).thenReturn("open");

        heartbeat.register(open);
        heartbeat.pingAll();

        verify(open).sendPing();
    }

    @Test
    @DisplayName("pingAll skips and de-registers closed sessions")
    void pingAll_dropsClosedSessions() throws Exception {
        WsSession closed = mock(WsSession.class);
        when(closed.isOpen()).thenReturn(false);
        when(closed.getId()).thenReturn("closed");

        heartbeat.register(closed);
        heartbeat.pingAll();
        heartbeat.pingAll();

        verify(closed, never()).sendMessage(any());
    }

    @Test
    @DisplayName("unregister stops a session from being pinged")
    void unregister_stopsPings() throws Exception {
        WsSession open = mock(WsSession.class);
        when(open.isOpen()).thenReturn(true);
        when(open.getId()).thenReturn("open");

        heartbeat.register(open);
        heartbeat.unregister(open);
        heartbeat.pingAll();

        verify(open, never()).sendMessage(any());
    }

    @Test
    @DisplayName("a send failure de-registers the broken session")
    void pingFailure_dropsSession() throws Exception {
        WsSession broken = mock(WsSession.class);
        when(broken.isOpen()).thenReturn(true);
        when(broken.getId()).thenReturn("broken");
        org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
                .when(broken).sendPing();

        heartbeat.register(broken);
        heartbeat.pingAll();   // throws internally, drops session
        heartbeat.pingAll();   // already gone — no second attempt

        verify(broken, times(1)).sendPing();
    }
}
