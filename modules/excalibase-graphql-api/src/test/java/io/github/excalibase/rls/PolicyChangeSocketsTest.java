package io.github.excalibase.rls;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A policy-change event always drops the cached permissions, but the project's sockets close only when the
 * document that is read back is not the one they were opened under. Studio's SQL editor, for one, publishes the
 * event after every statement, and a plain INSERT must not kick every realtime client of the project.
 */
class PolicyChangeSocketsTest {

    private static final String PROJECT = "proj-abc";
    private static final String SUBJECT = "policies.proj-abc.changed";

    private PermissionProvider permissions;
    private ProjectSockets sockets;
    private PolicyChangeSubscriber subscriber;

    private static PermissionSet document(long version) {
        return new PermissionSet(PROJECT, version, List.of(), List.of(), List.of());
    }

    @BeforeEach
    void wire() {
        permissions = mock(PermissionProvider.class);
        sockets = mock(ProjectSockets.class);
        when(sockets.hasSockets(PROJECT)).thenReturn(true);
        subscriber = new PolicyChangeSubscriber(List.of(permissions), permissions, sockets, false,
                "nats://localhost:4222", "", "", "");
    }

    @Test
    @DisplayName("an event that leaves the permissions as they were closes no socket")
    void identicalPermissionsKeepTheSocketsOpen() {
        when(permissions.cached(PROJECT)).thenReturn(Optional.of(document(7)));
        when(permissions.permissionsFor(PROJECT)).thenReturn(document(7));

        subscriber.evictFor(SUBJECT);

        verify(permissions).evict(PROJECT);
        verify(sockets, never()).closeForPermissionChange(any());
    }

    @Test
    @DisplayName("a real change, such as a revoked select, closes the project's sockets")
    void changedPermissionsCloseTheSockets() {
        when(permissions.cached(PROJECT)).thenReturn(Optional.of(document(7)));
        when(permissions.permissionsFor(PROJECT)).thenReturn(document(8));

        subscriber.evictFor(SUBJECT);

        verify(sockets).closeForPermissionChange(PROJECT);
    }

    @Test
    @DisplayName("when the permissions cannot be read back the sockets close, as they always did")
    void unreadablePermissionsCloseTheSockets() {
        when(permissions.cached(PROJECT)).thenReturn(Optional.of(document(7)));
        when(permissions.permissionsFor(PROJECT)).thenThrow(new PermissionsUnavailableException("down"));

        subscriber.evictFor(SUBJECT);

        verify(sockets).closeForPermissionChange(PROJECT);
    }

    @Test
    @DisplayName("with no earlier document to compare to, the sockets close")
    void noEarlierDocumentClosesTheSockets() {
        when(permissions.cached(PROJECT)).thenReturn(Optional.empty());
        when(permissions.permissionsFor(PROJECT)).thenReturn(document(7));

        subscriber.evictFor(SUBJECT);

        verify(sockets).closeForPermissionChange(PROJECT);
    }

    @Test
    @DisplayName("a project with no open socket costs no permission read")
    void noSocketsNoRead() {
        when(sockets.hasSockets(PROJECT)).thenReturn(false);

        subscriber.evictFor(SUBJECT);

        verify(permissions, never()).permissionsFor(any());
        verify(sockets, never()).closeForPermissionChange(any());
    }
}
