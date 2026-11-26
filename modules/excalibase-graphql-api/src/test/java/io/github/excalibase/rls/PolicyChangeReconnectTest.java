package io.github.excalibase.rls;

import io.github.excalibase.permissions.PermissionVersions;
import io.nats.client.ConnectionListener.Events;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A signal sent while NATS was unreachable is missed, so a reconnect catches up like a late first connect (EXC-831). */
class PolicyChangeReconnectTest {

    private final AtomicInteger catchUps = new AtomicInteger();
    private final ProjectSockets sockets = mock(ProjectSockets.class);
    private final PermissionVersions versions = mock(PermissionVersions.class);

    private PolicyChangeSubscriber subscriber() {
        return new PolicyChangeSubscriber(List.of(), versions, sockets,
                new PolicyChangeSubscriber.NatsSettings(true, "nats://localhost:4222", "", "", ""),
                catchUps::incrementAndGet);
    }

    @Test
    void aReconnect_evictsEverythingHeld_andSettlesTheOpenSockets() {
        when(sockets.projectsWithSockets()).thenReturn(Set.of("p1"));
        when(versions.current("p1")).thenReturn(OptionalLong.of(7));

        subscriber().connectionEvent(Events.RECONNECTED);

        assertThat(catchUps).hasValue(1);
        verify(sockets).permissionsAre("p1", 7);
    }

    @Test
    void aSocketWhoseDocumentCannotBeRead_closesAfterAReconnect() {
        when(sockets.projectsWithSockets()).thenReturn(Set.of("p1"));
        when(versions.current("p1")).thenReturn(OptionalLong.empty());

        subscriber().connectionEvent(Events.RECONNECTED);

        verify(sockets).closeForPermissionChange("p1");
    }

    @Test
    void otherConnectionEvents_catchUpNothing() {
        PolicyChangeSubscriber subscriber = subscriber();

        for (Events event : List.of(Events.CONNECTED, Events.DISCONNECTED, Events.CLOSED, Events.RESUBSCRIBED)) {
            subscriber.connectionEvent(event);
        }

        assertThat(catchUps).hasValue(0);
        verify(sockets, never()).permissionsAre(anyString(), anyLong());
    }
}
