package io.github.excalibase.rls;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NATS that is down when the engine boots is tried again until it answers, and not after stop. (EXC-772) */
class PolicyChangeSubscriberRetryTest {

    private static final Duration SHORT = Duration.ofMillis(20);
    private static final Duration WITHIN = Duration.ofSeconds(5);

    private PolicyChangeSubscriber subscriber;

    @AfterEach
    void stop() {
        if (subscriber != null) {
            subscriber.stop();
        }
    }

    private static Connection natsThatAnswers() {
        Connection connection = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher(any())).thenReturn(dispatcher);
        return connection;
    }

    private PolicyChangeSubscriber subscriberOver(PolicyChangeSubscriber.Connector connector) {
        return subscriberOver(connector, () -> { });
    }

    private PolicyChangeSubscriber subscriberOver(PolicyChangeSubscriber.Connector connector, Runnable catchUp) {
        subscriber = new PolicyChangeSubscriber(List.of(), null, null,
                new PolicyChangeSubscriber.NatsSettings(true, "nats://down:4222", "", "", ""),
                new PolicyChangeSubscriber.Wiring(connector, SHORT, catchUp));
        return subscriber;
    }

    @Test
    void natsDownAtBoot_isTriedAgainUntilItAnswers() {
        AtomicInteger attempts = new AtomicInteger();
        Connection connection = natsThatAnswers();

        subscriberOver(options -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("no servers available");
            }
            return connection;
        }).start();

        await().atMost(WITHIN).untilAsserted(() -> assertThat(subscriber.isRunning()).isTrue());
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void afterStop_itDoesNotConnectAgain() {
        AtomicInteger attempts = new AtomicInteger();

        subscriberOver(options -> {
            attempts.incrementAndGet();
            throw new IOException("no servers available");
        }).start();
        await().atMost(WITHIN).until(() -> attempts.get() >= 2);
        subscriber.stop();
        int atStop = attempts.get();

        await().during(Duration.ofMillis(200)).atMost(WITHIN).until(() -> attempts.get() <= atStop + 1);
        assertThat(subscriber.isRunning()).isFalse();
    }

    @Test
    void stopClosesTheConnection() throws Exception {
        Connection connection = natsThatAnswers();

        subscriberOver(options -> connection).start();
        subscriber.stop();

        verify(connection).close();
    }

    @Test
    void aConnectionThatCouldNotSubscribe_isClosedBeforeTheNextAttempt() throws Exception {
        Connection broken = mock(Connection.class);
        when(broken.createDispatcher(any())).thenThrow(new IllegalStateException("subscribe failed"));
        Connection good = natsThatAnswers();
        AtomicInteger attempts = new AtomicInteger();

        subscriberOver(options -> attempts.incrementAndGet() == 1 ? broken : good).start();

        await().atMost(WITHIN).untilAsserted(() -> assertThat(subscriber.isRunning()).isTrue());
        verify(broken).close();
    }

    @Test
    void connectingLate_evictsEverythingHeld_butConnectingAtBootDoesNot() {
        AtomicInteger caughtUp = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        Connection connection = natsThatAnswers();

        subscriberOver(options -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IOException("no servers available");
            }
            return connection;
        }, caughtUp::incrementAndGet).start();

        await().atMost(WITHIN).untilAsserted(() -> assertThat(caughtUp.get()).isEqualTo(1));
        PolicyChangeSubscriber atBoot = subscriberOver(options -> connection, caughtUp::incrementAndGet);
        atBoot.start();
        assertThat(caughtUp.get()).isEqualTo(1);
    }
}
