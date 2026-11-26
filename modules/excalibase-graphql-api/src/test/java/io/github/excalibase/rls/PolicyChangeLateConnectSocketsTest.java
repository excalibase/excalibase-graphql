package io.github.excalibase.rls;

import io.github.excalibase.permissions.PermissionProvider;
import io.github.excalibase.permissions.PermissionSet;
import io.github.excalibase.permissions.PermissionVersions;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A permission change made while NATS was down is never signalled. When NATS comes up late, the sockets opened
 * before the outage must still learn of it: they are settled against the document as it is now, not left on the
 * one they opened under. (EXC-707)
 */
class PolicyChangeLateConnectSocketsTest {

    private static final String PROJECT = "proj-abc";
    private static final Duration SHORT = Duration.ofMillis(20);
    private static final Duration WITHIN = Duration.ofSeconds(5);

    private PolicyChangeSubscriber subscriber;

    @AfterEach
    void stop() {
        if (subscriber != null) {
            subscriber.stop();
        }
    }

    /** Sockets that close as the engine's do: when told a version other than the one they opened under. */
    private static final class FakeSockets implements ProjectSockets {
        private final Map<String, Long> openedUnder = new ConcurrentHashMap<>();
        private final Set<String> closed = ConcurrentHashMap.newKeySet();

        void open(String project, long version) {
            openedUnder.put(project, version);
        }

        boolean isClosed(String project) {
            return closed.contains(project);
        }

        @Override
        public boolean hasSockets(String projectId) {
            return openedUnder.containsKey(projectId) && !closed.contains(projectId);
        }

        @Override
        public Set<String> projectsWithSockets() {
            return openedUnder.keySet().stream().filter(this::hasSockets).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public void permissionsAre(String projectId, long version) {
            if (hasSockets(projectId) && openedUnder.get(projectId) != version) {
                closed.add(projectId);
            }
        }

        @Override
        public void closeForPermissionChange(String projectId) {
            closed.add(projectId);
        }
    }

    private static Connection natsThatAnswers() {
        Connection connection = mock(Connection.class);
        when(connection.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        return connection;
    }

    private static PermissionSet document(long version) {
        return new PermissionSet(PROJECT, version, List.of(), List.of(), List.of());
    }

    /** NATS refuses twice, then answers; the permissions are at {@code current} by then. */
    private void natsComesUpLate(FakeSockets sockets, PermissionProvider permissions) {
        AtomicInteger attempts = new AtomicInteger();
        Connection connection = natsThatAnswers();
        subscriber = new PolicyChangeSubscriber(List.of(permissions), new PermissionVersions(permissions), sockets,
                new PolicyChangeSubscriber.NatsSettings(true, "nats://down:4222", "", "", ""),
                new PolicyChangeSubscriber.Wiring(options -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IOException("no servers available");
                    }
                    return connection;
                }, SHORT, permissions::evictAll));
        subscriber.start();
        await().atMost(WITHIN).untilAsserted(() -> assertThat(subscriber.isRunning()).isTrue());
    }

    private static PermissionProvider permissionsAt(AtomicLong version) {
        PermissionProvider permissions = mock(PermissionProvider.class);
        when(permissions.permissionsFor(PROJECT)).thenAnswer(call -> document(version.get()));
        return permissions;
    }

    @Test
    @DisplayName("a socket opened before the outage is closed when the permissions changed during it")
    void changedDuringTheOutage_closesTheSocket() {
        FakeSockets sockets = new FakeSockets();
        sockets.open(PROJECT, 1);
        AtomicLong current = new AtomicLong(2);

        natsComesUpLate(sockets, permissionsAt(current));

        await().atMost(WITHIN).untilAsserted(() -> assertThat(sockets.isClosed(PROJECT)).isTrue());
    }

    @Test
    @DisplayName("a socket whose document did not change is left open")
    void unchangedDuringTheOutage_leavesTheSocketOpen() {
        FakeSockets sockets = new FakeSockets();
        sockets.open(PROJECT, 2);
        AtomicLong current = new AtomicLong(2);

        natsComesUpLate(sockets, permissionsAt(current));

        assertThat(sockets.isClosed(PROJECT)).isFalse();
    }

    @Test
    @DisplayName("when the document cannot be read back, the project's sockets close")
    void unreadableDocument_closesTheSocket() {
        FakeSockets sockets = new FakeSockets();
        sockets.open(PROJECT, 1);
        PermissionProvider permissions = mock(PermissionProvider.class);
        when(permissions.permissionsFor(PROJECT)).thenThrow(new PermissionsUnavailableException("control plane down"));

        natsComesUpLate(sockets, permissions);

        await().atMost(WITHIN).untilAsserted(() -> assertThat(sockets.isClosed(PROJECT)).isTrue());
    }
}
