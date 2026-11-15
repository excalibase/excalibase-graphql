package io.github.excalibase.config.ws;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionVersions;
import io.github.excalibase.rls.PolicyChangeSubscriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The close after a permission change is judged by what each socket was opened under, not by the cache. */
class StaleCacheCloseTest {

    private static WebSocketSession socket(WsRuntime runtime, long openedUnder) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-" + System.nanoTime());
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(GraphQLWebSocketHandler.SESSION_PROJECT_KEY, "p1");
        when(session.getAttributes()).thenReturn(attributes);
        runtime.admit(session);
        runtime.openedUnder(session, openedUnder, runtime.permissionGeneration(session));
        return session;
    }

    private static void eventWithTheCacheAt(WsRuntime runtime, long version) {
        PermissionProvider permissions = mock(PermissionProvider.class);
        when(permissions.permissionsFor("p1")).thenReturn(new PermissionSet("p1", version, List.of(), List.of(), List.of()));
        new PolicyChangeSubscriber(List.of(permissions), new PermissionVersions(permissions), runtime, false,
                "nats://localhost:4222", "", "", "").evictFor("policies.p1.changed");
    }

    @Test
    @DisplayName("a socket opened under v7 closes when the event finds v8, even if the cache already held v8")
    void socketOpenedUnderTheOldDocumentCloses() throws Exception {
        WsRuntime runtime = WsRuntime.inline();
        WebSocketSession stale = socket(runtime, 7);

        eventWithTheCacheAt(runtime, 8);

        verify(stale).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("a socket opened under the current document stays open")
    void socketOpenedUnderTheCurrentDocumentStays() throws Exception {
        WsRuntime runtime = WsRuntime.inline();
        WebSocketSession current = socket(runtime, 8);

        eventWithTheCacheAt(runtime, 8);

        verify(current, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("one subscription under an old document is enough to close the socket; a plan-less socket is left alone")
    void mixedSubscriptions() throws Exception {
        WsRuntime runtime = WsRuntime.inline();
        WebSocketSession mixed = socket(runtime, 8);
        runtime.openedUnder(mixed, 7, runtime.permissionGeneration(mixed));
        WebSocketSession untracked = socket(runtime, -1);

        eventWithTheCacheAt(runtime, 8);

        verify(mixed).close(any(CloseStatus.class));
        verify(untracked, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("a refresh that finds a different document closes the sockets opened under the old one")
    void permissionsAreClosesByVersion() throws Exception {
        WsRuntime runtime = WsRuntime.inline();
        WebSocketSession old = socket(runtime, 7);
        WebSocketSession fresh = socket(runtime, 9);

        runtime.permissionsAre("p1", 9);

        verify(old).close(any(CloseStatus.class));
        verify(fresh, never()).close(any(CloseStatus.class));
    }
}
