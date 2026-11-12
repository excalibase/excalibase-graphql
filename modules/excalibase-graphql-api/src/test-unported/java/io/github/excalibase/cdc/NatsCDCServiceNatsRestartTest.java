package io.github.excalibase.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Realtime keeps receiving the change stream across a NATS restart, resuming
 * from the last message it saw rather than skipping what was published while
 * it was reconnecting.
 */
class NatsCDCServiceNatsRestartTest {

    private static final String STREAM = "CDC";
    // Longer than a consumer's inactivity threshold and the client's heartbeat checks.
    private static final Duration OUTAGE = Duration.ofSeconds(15);

    private final List<CDCEvent> delivered = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GenericContainer<?> nats;
    private NatsCDCService service;
    private String url;

    @BeforeEach
    void startNats() throws Exception {
        int port = freePort();
        nats = new GenericContainer<>("nats:2.10")
                .withCommand("-js")
                .withExposedPorts(4222)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(4222))))
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        nats.start();
        url = "nats://localhost:" + port;
        try (Connection admin = connect()) {
            JetStreamManagement jsm = admin.jetStreamManagement();
            jsm.addStream(StreamConfiguration.builder()
                    .name(STREAM).subjects("cdc.>").storageType(StorageType.File).build());
        }
    }

    @AfterEach
    void stopAll() {
        if (service != null) {
            service.stop();
        }
        nats.stop();
    }

    @Test
    @DisplayName("events published after a NATS outage still reach realtime, each once")
    void resumesAfterNatsOutage() throws Exception {
        SubscriptionService subscriptions = new SubscriptionService() {
            @Override
            public void publish(String tenantId, CDCEvent event) {
                delivered.add(event);
            }
        };
        service = new NatsCDCService(subscriptions, objectMapper);
        ReflectionTestUtils.setField(service, "natsEnabled", true);
        ReflectionTestUtils.setField(service, "natsUrl", url);
        ReflectionTestUtils.setField(service, "streamName", STREAM);
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        service.start();
        assertThat(service.isRunning()).isTrue();

        publish(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered.size() == 1);

        nats.getDockerClient().stopContainerCmd(nats.getContainerId()).exec();
        Thread.sleep(OUTAGE.toMillis());
        nats.getDockerClient().startContainerCmd(nats.getContainerId()).exec();
        for (int id = 2; id <= 5; id++) {
            publish(id);
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> delivered.size() >= 5);
        Thread.sleep(2000);
        assertThat(delivered).extracting(CDCEvent::data)
                .containsExactly("{\"id\":1}", "{\"id\":2}", "{\"id\":3}", "{\"id\":4}", "{\"id\":5}");
    }

    private void publish(int id) throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":" + id + "}", id);
        byte[] body = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
            try (Connection publisher = connect()) {
                publisher.jetStream().publish("cdc.public.customers", body);
                return true;
            }
        });
    }

    private Connection connect() throws Exception {
        return Nats.connect(new Options.Builder().server(url).connectionTimeout(Duration.ofSeconds(2)).build());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
