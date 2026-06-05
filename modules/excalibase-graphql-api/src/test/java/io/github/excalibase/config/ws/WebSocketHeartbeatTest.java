package io.github.excalibase.config.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

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
        WebSocketSession open = mock(WebSocketSession.class);
        when(open.isOpen()).thenReturn(true);
        when(open.getId()).thenReturn("open");

        heartbeat.register(open);
        heartbeat.pingAll();

        verify(open).sendMessage(any(PingMessage.class));
    }

    @Test
    @DisplayName("pingAll skips and de-registers closed sessions")
    void pingAll_dropsClosedSessions() throws Exception {
        WebSocketSession closed = mock(WebSocketSession.class);
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
        WebSocketSession open = mock(WebSocketSession.class);
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
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.isOpen()).thenReturn(true);
        when(broken.getId()).thenReturn("broken");
        org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
                .when(broken).sendMessage(any(PingMessage.class));

        heartbeat.register(broken);
        heartbeat.pingAll();   // throws internally, drops session
        heartbeat.pingAll();   // already gone — no second attempt

        verify(broken, times(1)).sendMessage(any(PingMessage.class));
    }
}
