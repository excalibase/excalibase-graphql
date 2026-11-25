package io.github.excalibase.rls;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionVersions;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A policy-change event always drops the cached permissions, then tells the project's sockets which permission
 * document is current; only sockets opened under another one close. Studio's SQL editor publishes the event after
 * every statement, and a plain INSERT must not kick every realtime client of the project.
 */
class PolicyChangeSocketsTest {

    private static final String PROJECT = "proj-abc";
    private static final String SUBJECT = "policies.proj-abc.changed";

    private PermissionProvider permissions;
    private ProjectSockets sockets;
    private ProjectCacheEvictor evictor;

    private PolicyChangeSubscriber subscriber() {
        return new PolicyChangeSubscriber(List.of(permissions, evictor), new PermissionVersions(permissions), sockets,
                false, "nats://localhost:4222", "", "", "");
    }

    private static PermissionSet document(long version) {
        return new PermissionSet(PROJECT, version, List.of(), List.of(), List.of());
    }

    @BeforeEach
    void wire() {
        permissions = mock(PermissionProvider.class);
        evictor = mock(ProjectCacheEvictor.class);
        sockets = mock(ProjectSockets.class);
        when(sockets.hasSockets(PROJECT)).thenReturn(true);
    }

    @Test
    @DisplayName("the sockets are told the version read back, and nothing is closed outright")
    void socketsAreToldTheCurrentVersion() {
        when(permissions.permissionsFor(PROJECT)).thenReturn(document(8));

        subscriber().evictFor(SUBJECT);

        verify(permissions).evict(PROJECT);
        verify(sockets).permissionsAre(PROJECT, 8);
        verify(sockets, never()).closeForPermissionChange(any());
    }

    @Test
    @DisplayName("when the permissions cannot be read back every socket of the project closes")
    void unreadablePermissionsCloseTheSockets() {
        when(permissions.permissionsFor(PROJECT)).thenThrow(new PermissionsUnavailableException("down"));

        subscriber().evictFor(SUBJECT);

        verify(sockets).closeForPermissionChange(PROJECT);
        verify(sockets, never()).permissionsAre(any(), anyLong());
    }

    @Test
    @DisplayName("an unexpected error while reading back also closes them")
    void unexpectedErrorClosesTheSockets() {
        when(permissions.permissionsFor(PROJECT)).thenThrow(new IllegalStateException("boom"));

        subscriber().evictFor(SUBJECT);

        verify(sockets).closeForPermissionChange(PROJECT);
    }

    @Test
    @DisplayName("an evictor that throws still leaves the sockets closed, the other holders evicted, and the error raised")
    void aFailingEvictorClosesTheSockets() {
        org.mockito.Mockito.doThrow(new IllegalStateException("evictor")).when(evictor).evict(PROJECT);

        assertThatThrownBy(() -> subscriber().evictFor(SUBJECT)).isInstanceOf(IllegalStateException.class);

        verify(permissions).evict(PROJECT);
        verify(sockets).closeForPermissionChange(PROJECT);
        verify(sockets, never()).permissionsAre(any(), anyLong());
    }

    @Test
    @DisplayName("a project with no open socket costs no permission read")
    void noSocketsNoRead() {
        when(sockets.hasSockets(PROJECT)).thenReturn(false);

        subscriber().evictFor(SUBJECT);

        verify(permissions, never()).permissionsFor(any());
        verify(sockets, never()).closeForPermissionChange(any());
        verify(sockets, never()).permissionsAre(any(), anyLong());
    }
}
