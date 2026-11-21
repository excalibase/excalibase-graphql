package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Past its connection bound the engine refuses at once with a 503 instead of leaving the client to time out. */
class OverloadRefusalIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int CPUS = Runtime.getRuntime().availableProcessors();
    private static final int BOUND = CPUS * 2;
    private static final int MANAGEMENT_PORT = FreePort.find();

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(
                Map.entry("spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent"),
                Map.entry("spring.datasource.username", "fake"),
                Map.entry("spring.datasource.password", "fake"),
                Map.entry("app.project-id", PROJECT),
                Map.entry("app.security.jwt-enabled", "false"),
                Map.entry("management.health.db.enabled", "false"),
                Map.entry("app.security.insecure-dev-mode", "true"),
                Map.entry("app.database-type", "postgres"),
                Map.entry("app.overload.max-connections-per-cpu", "2"),
                Map.entry("app.overload.admission-wait-ms", "100"),
                Map.entry("micronaut.server.port", "-1"),
                Map.entry("endpoints.all.port", Integer.toString(MANAGEMENT_PORT)),
                Map.entry("test.slow-endpoint", "true"));
    }

    private final HttpClient client = HttpClient.newHttpClient();

    private List<Socket> saturate() throws IOException {
        List<Socket> held = new ArrayList<>();
        for (int i = 0; i < BOUND; i++) {
            held.add(new Socket("localhost", server.getPort()));
        }
        awaitFull();
        return held;
    }

    /** Slots of earlier sockets free asynchronously; the gate is full once the new ones hold all of them. */
    private void awaitFull() throws IOException {
        long giveUp = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < giveUp) {
            try {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + MANAGEMENT_PORT + "/actuator/metrics/tomcat.connections.current")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (MetricValue.of(response.body()) >= BOUND) {
                    return;
                }
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("the gate never filled");
    }

    private static void closeAll(List<Socket> sockets) throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
    }

    private String exchange(String request) throws IOException {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private double refused() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + MANAGEMENT_PORT + "/actuator/metrics/excalibase.connections.refused")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return MetricValue.of(response.body());
    }

    @Test
    @DisplayName("a request over the bound is refused within 500 ms with 503, Retry-After and a GraphQL error body")
    void overBound_isRefusedFast() throws Exception {
        List<Socket> held = saturate();
        try {
            double before = refused();
            long start = System.nanoTime();
            String answer = exchange("POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(answer).startsWith("HTTP/1.1 503");
            assertThat(answer).containsIgnoringCase("Retry-After: ");
            assertThat(answer).contains("\"errors\"").contains("SERVICE_OVERLOADED");
            assertThat(elapsedMillis).isLessThan(500);
            assertThat(refused()).isEqualTo(before + 1);
        } finally {
            closeAll(held);
        }
    }

    @Test
    @DisplayName("a WebSocket upgrade over the bound is refused with a plain 503, not left hanging")
    void upgradeOverBound_isRefused() throws Exception {
        List<Socket> held = saturate();
        try {
            long start = System.nanoTime();
            String answer = exchange("GET /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n");
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
            assertThat(answer).startsWith("HTTP/1.1 503").containsIgnoringCase("Retry-After: ");
        } finally {
            closeAll(held);
        }
    }

    @Test
    @DisplayName("requests under the bound are served, and a slot freed after a refusal is usable again")
    void underBound_isServed() throws Exception {
        assertThat(exchange("GET /" + PROJECT + "/slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 200");
        List<Socket> held = saturate();
        closeAll(held);
        Thread.sleep(200);
        assertThat(exchange("GET /" + PROJECT + "/slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
                .startsWith("HTTP/1.1 200");
    }

    @Test
    @DisplayName("the management port still answers while the main port refuses")
    void managementStaysUp() throws Exception {
        List<Socket> held = saturate();
        try {
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + MANAGEMENT_PORT + "/actuator/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        } finally {
            closeAll(held);
        }
    }
}
