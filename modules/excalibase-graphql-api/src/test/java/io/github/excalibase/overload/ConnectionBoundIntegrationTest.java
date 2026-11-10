package io.github.excalibase.overload;

import io.github.excalibase.ExcalibaseGraphqlApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real server flooded past its connection bound serves everyone, never more than the bound at once. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {ExcalibaseGraphqlApplication.class, ConnectionBoundIntegrationTest.SlowEndpoint.class})
class ConnectionBoundIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int CPUS = Runtime.getRuntime().availableProcessors();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("management.health.db.enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.overload.max-connections-per-cpu", () -> "2");
        registry.add("app.overload.accept-count-per-cpu", () -> "256");
        registry.add("app.overload.max-keep-alive-requests", () -> "2");
        registry.add("app.overload.keep-alive-timeout-ms", () -> "300");
    }

    @TestConfiguration
    @RestController
    static class SlowEndpoint {
        @GetMapping("/" + PROJECT + "/slow")
        String slow() throws InterruptedException {
            PEAK.accumulateAndGet(ACTIVE.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200);
            } finally {
                ACTIVE.decrementAndGet();
            }
            return "ok";
        }
    }

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("a flood well past the bound is served in full, with at most the bound in flight at once")
    void flood_isQueuedNotDropped() throws Exception {
        int flood = CPUS * 20;
        List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
        for (int i = 0; i < flood; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + PROJECT + "/slow"))
                    .timeout(Duration.ofSeconds(60)).GET().build();
            responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> response : responses) {
            assertThat(response.get().statusCode()).isEqualTo(200);
        }
        assertThat(PEAK.get()).isLessThanOrEqualTo(CPUS * 2);
    }

    @Test
    @DisplayName("a keep-alive connection is closed by the server after the configured number of requests")
    void keepAlive_rotates() throws Exception {
        String request = "GET /" + PROJECT + "/slow HTTP/1.1\r\nHost: localhost\r\n\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((request + request + request).getBytes());
            String received = new String(socket.getInputStream().readAllBytes());
            assertThat(received.split("HTTP/1.1 200", -1).length - 1).isEqualTo(2);
            assertThat(received).containsIgnoringCase("Connection: close");
        }
    }

    private CompletableFuture<WebSocket> openSocket() {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/" + PROJECT + "/graphql"), new WebSocket.Listener() { });
    }

    private List<WebSocket> openSockets(int count) throws Exception {
        List<WebSocket> sockets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sockets.add(openSocket().get(10, TimeUnit.SECONDS));
        }
        return sockets;
    }

    private void closeAll(List<WebSocket> sockets) {
        sockets.forEach(socket -> socket.abort());
    }

    @Test
    @DisplayName("open websockets take their share of the bound but do not starve HTTP clients")
    void websockets_doNotStarveHttp() throws Exception {
        List<WebSocket> sockets = openSockets(CPUS);
        try {
            PEAK.set(0);
            List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < CPUS * 20; i++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + PROJECT + "/slow"))
                        .timeout(Duration.ofSeconds(60)).GET().build();
                responses.add(HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            for (CompletableFuture<HttpResponse<String>> response : responses) {
                assertThat(response.get().statusCode()).isEqualTo(200);
            }
            assertThat(PEAK.get()).isLessThanOrEqualTo(CPUS);
            assertThat(sockets).allMatch(socket -> !socket.isInputClosed());
        } finally {
            closeAll(sockets);
        }
    }

    @Test
    @DisplayName("websockets past the bound wait in the accept queue and are admitted when one closes")
    void websockets_areBoundedToo() throws Exception {
        List<WebSocket> sockets = openSockets(CPUS * 2);
        try {
            CompletableFuture<WebSocket> extra = openSocket();
            assertThatThrownBy(() -> extra.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            sockets.get(0).abort();
            sockets.add(extra.get(10, TimeUnit.SECONDS));
        } finally {
            closeAll(sockets);
        }
    }

    @Test
    @DisplayName("the connection bound and the open connections are readable as metrics")
    void boundIsObservable() throws Exception {
        HttpResponse<String> max = client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/metrics/tomcat.connections.config.max")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(max.statusCode()).isEqualTo(200);
        assertThat(max.body()).contains("\"value\":" + CPUS * 2);
        assertThat(client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/metrics/tomcat.connections.current")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("health on the management port answers within a second while the main port is full")
    void healthAnswersWhileBoundIsFull() throws Exception {
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < CPUS * 2 + 4; i++) {
                held.add(new Socket("localhost", port));
            }
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + managementPort + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("the management port carries no connection bound of its own")
    void managementPortIsNotBounded() throws Exception {
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < CPUS * 2 + 4; i++) {
                held.add(new Socket("localhost", managementPort));
            }
            HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + managementPort + "/actuator/health"))
                    .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }
}
