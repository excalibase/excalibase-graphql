package io.github.excalibase;

import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * With request metrics on, project traffic shows up per project on the internal port's
 * {@code GET /metrics} in exactly the series provisioning parses, and nowhere on the public port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RequestMetricsIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int METRICS_PORT = freePort();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.nats.enabled", () -> false);
        registry.add("app.request-metrics.enabled", () -> "true");
        registry.add("app.request-metrics.port", () -> METRICS_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SubscriptionService subscriptionService;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("GraphQL, REST and realtime traffic of a known project is scraped from the internal port")
    void scrapeShowsProjectSeries() throws Exception {
        assertThat(postGraphql("{\"query\":\"{ __typename }\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> withErrors = postGraphql("{\"query\":\"{ noSuchField { id } }\"}");
        assertThat(withErrors.statusCode()).isEqualTo(200);
        assertThat(withErrors.body()).contains("errors");
        assertThat(get(port, "/" + PROJECT + "/api/v1/products").statusCode()).isEqualTo(200);
        assertThat(get(port, "/unknown-proj/api/v1/products").statusCode()).isEqualTo(404);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = subscribe(received);
        String scrapeWhileOpen;
        try {
            subscriptionService.publish(null, new CDCEvent("INSERT", "public", "products", "{\"id\":99}", 0L));
            assertThat(received.poll(5, TimeUnit.SECONDS)).contains("\"next\"");
            scrapeWhileOpen = scrape();
        } finally {
            session.close();
        }

        assertThat(scrapeWhileOpen).contains(
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"ok\",project=\"test-proj\"} 1",
                "excalibase_requests_seconds_count{family=\"graphql\",outcome=\"client_error\",project=\"test-proj\"} 1",
                "excalibase_requests_seconds_count{family=\"rest\",outcome=\"ok\",project=\"test-proj\"} 1",
                "excalibase_requests_seconds_bucket{family=\"rest\",outcome=\"ok\",project=\"test-proj\",le=\"10.0\"} 1",
                "excalibase_requests_seconds_bucket{family=\"rest\",outcome=\"ok\",project=\"test-proj\",le=\"+Inf\"} 1",
                "excalibase_requests_seconds_sum{family=\"rest\",outcome=\"ok\",project=\"test-proj\"} ",
                "excalibase_realtime_connections{project=\"test-proj\"} 1",
                "excalibase_realtime_connections_opened_total{project=\"test-proj\"} 1.0",
                "excalibase_realtime_messages_total{project=\"test-proj\"} 1.0",
                "excalibase_request_metrics_info{instance=\"",
                "excalibase_request_metrics_start_time_seconds{instance=\"",
                "excalibase_request_metrics_dropped_total 0.0")
                .doesNotContain("unknown-proj");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(scrape()).contains("excalibase_realtime_connections{project=\"test-proj\"} 0"));
    }

    @Test
    @DisplayName("the internal port serves only GET /metrics, the public port never serves the scrape")
    void portsAreSeparate() throws Exception {
        HttpResponse<String> internalScrape = get(METRICS_PORT, "/metrics");
        assertThat(internalScrape.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("text/plain").contains("version=0.0.4");

        for (String path : List.of("/" + PROJECT + "/api/v1/products", "/actuator/health", "/metrics/x")) {
            HttpResponse<String> refused = get(METRICS_PORT, path);
            assertThat(refused.statusCode()).as(path).isEqualTo(404);
            assertThat(refused.body()).as(path).isEmpty();
        }
        for (String path : List.of("/metrics", "/actuator/prometheus", "/actuator/metrics/excalibase.requests")) {
            assertThat(get(port, path).body()).as(path).doesNotContain("excalibase_requests")
                    .doesNotContain("\"name\":\"excalibase.requests\"");
        }
    }

    private String scrape() throws IOException, InterruptedException {
        HttpResponse<String> response = get(METRICS_PORT, "/metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> postGraphql(String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + PROJECT + "/graphql"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int targetPort, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private WebSocketSession subscribe(BlockingQueue<String> received) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setSecWebSocketProtocol(List.of("graphql-transport-ws"));
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.add(message.getPayload());
            }
        }, headers, URI.create("ws://localhost:" + port + "/" + PROJECT + "/graphql")).get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage("{\"type\":\"connection_init\"}"));
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("connection_ack");
        session.sendMessage(new TextMessage("{\"type\":\"subscribe\",\"id\":\"s1\",\"payload\":"
                + "{\"query\":\"subscription { publicProductsChanges { operation } }\"}}"));
        await().atMost(Duration.ofSeconds(5)).until(() -> subscriptionService.hasSubscribers(null, "public_products"));
        return session;
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
