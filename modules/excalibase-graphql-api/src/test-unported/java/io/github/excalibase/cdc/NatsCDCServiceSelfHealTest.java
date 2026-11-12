package io.github.excalibase.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Realtime does not depend on the order things start in or on NATS keeping its
 * consumer (EXC-612): it subscribes once the CDC stream exists, and subscribes
 * again when the server no longer has its consumer, as after a NATS restart
 * the client took longer than the consumer's inactivity threshold to survive.
 */
class NatsCDCServiceSelfHealTest {

    private static final String STREAM = "CDC";

    private final List<CDCEvent> delivered = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GenericContainer<?> nats;
    private NatsCDCService service;
    private String url;

    @BeforeEach
    void startNats() {
        nats = new GenericContainer<>("nats:2.10")
                .withCommand("-js")
                .withExposedPorts(4222)
                .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        nats.start();
        url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
        service = new NatsCDCService(new SubscriptionService() {
            @Override
            public void publish(String tenantId, CDCEvent event) {
                delivered.add(event);
            }
        }, objectMapper);
        ReflectionTestUtils.setField(service, "natsEnabled", true);
        ReflectionTestUtils.setField(service, "natsUrl", url);
        ReflectionTestUtils.setField(service, "streamName", STREAM);
        ReflectionTestUtils.setField(service, "subjectPrefix", "cdc");
        ReflectionTestUtils.setField(service, "resubscribeInterval", Duration.ofSeconds(1));
    }

    @AfterEach
    void stopAll() {
        service.stop();
        nats.stop();
    }

    @Test
    @DisplayName("realtime started before the CDC stream exists receives changes once it does")
    void subscribesOnceTheStreamExists() throws Exception {
        service.start();
        assertThat(service.isRunning()).isFalse();

        try (Connection admin = connect()) {
            admin.jetStreamManagement().addStream(StreamConfiguration.builder()
                    .name(STREAM).subjects("cdc.>").storageType(StorageType.File).build());
        }
        await().atMost(Duration.ofSeconds(30)).until(service::isRunning);

        publish(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered.size() == 1);
    }

    @Test
    @DisplayName("a consumer the server dropped is made again and later changes arrive")
    void resubscribesWhenTheConsumerIsGone() throws Exception {
        try (Connection admin = connect()) {
            admin.jetStreamManagement().addStream(StreamConfiguration.builder()
                    .name(STREAM).subjects("cdc.>").storageType(StorageType.File).build());
        }
        service.start();
        assertThat(service.isRunning()).isTrue();
        publish(1);
        await().atMost(Duration.ofSeconds(15)).until(() -> delivered.size() == 1);

        try (Connection admin = connect()) {
            JetStreamManagement jsm = admin.jetStreamManagement();
            for (ConsumerInfo consumer : jsm.getConsumers(STREAM)) {
                jsm.deleteConsumer(STREAM, consumer.getName());
            }
        }

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> {
            publish(delivered.size() + 1);
            Thread.sleep(500);
            return delivered.size() >= 2;
        });
        assertThat(delivered.get(1).data()).isNotEqualTo(delivered.get(0).data());
    }

    private void publish(int id) throws Exception {
        CDCEvent event = new CDCEvent("INSERT", "public", "customers", "{\"id\":" + id + "}", id);
        byte[] body = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        try (Connection publisher = connect()) {
            publisher.jetStream().publish("cdc.public.customers", body);
        }
    }

    private Connection connect() throws Exception {
        return Nats.connect(new Options.Builder().server(url).connectionTimeout(Duration.ofSeconds(2)).build());
    }
}
