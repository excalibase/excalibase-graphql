package io.github.excalibase.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.excalibase.support.CapturedLogs;
import io.micronaut.context.ApplicationContext;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The subscriber is built by a factory method, so the container, not the class, must run its start and stop. */
class PolicyChangeSubscriberLifecycleTest {

    private static final String SECRET = "test-hmac-secret-0123456789abcdef0123456789";

    private GenericContainer<?> nats;
    private CapturedLogs logs;
    private Logger subscriberLog;

    @BeforeEach
    void startNats() {
        nats = new GenericContainer<>("nats:2.10")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        nats.start();
        subscriberLog = (Logger) LoggerFactory.getLogger(PolicyChangeSubscriber.class);
        logs = new CapturedLogs();
        logs.start();
        subscriberLog.addAppender(logs);
    }

    @AfterEach
    void stopNats() {
        subscriberLog.detachAppender(logs);
        nats.stop();
    }

    private ApplicationContext engine(boolean natsEnabled) {
        return ApplicationContext.run(Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.auth.hmac-secret", SECRET,
                "app.nats.enabled", String.valueOf(natsEnabled),
                "app.nats.url", "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222),
                "micronaut.server.port", "-1",
                "endpoints.all.port", "-1"));
    }

    @Test
    @DisplayName("starting the context subscribes to policy changes, a signal evicts, and closing it unsubscribes")
    void contextStartSubscribes_signalEvicts_closeStops() throws Exception {
        PolicyChangeSubscriber subscriber;
        try (ApplicationContext context = engine(true)) {
            subscriber = context.getBean(PolicyChangeSubscriber.class);
            assertThat(subscriber.isRunning()).as("start() ran when the context started").isTrue();

            try (Connection publisher = Nats.connect("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222))) {
                publisher.publish("policies.proj-lifecycle.changed", new byte[0]);
                publisher.flush(Duration.ofSeconds(5));
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(logs.events())
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("Policy change for project proj-lifecycle")));
        }
        assertThat(subscriber.isRunning()).as("stop() ran when the context closed").isFalse();
    }

    @Test
    @DisplayName("with NATS off the start still runs and says so")
    void natsOff_startStillRuns() {
        try (ApplicationContext context = engine(false)) {
            assertThat(context.getBean(PolicyChangeSubscriber.class)).isNotNull();
            assertThat(logs.events()).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.startsWith("Policy-change subscriber disabled"));
        }
    }
}
