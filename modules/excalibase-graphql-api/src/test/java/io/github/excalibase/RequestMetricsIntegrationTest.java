package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.excalibase.cdc.CDCEvent;
import io.github.excalibase.cdc.SubscriptionService;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.WsClient;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * With request metrics on, project traffic shows up per project on the internal port's
 * {@code GET /metrics} in exactly the series provisioning parses, and nowhere on the public port.
 */
class RequestMetricsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int METRICS_PORT = FreePort.find();
    private static final int MANAGEMENT_PORT = FreePort.find();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    @Inject
    SubscriptionService subscriptionService;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, PROJECT));
        properties.put("app.nats.enabled", "false");
        properties.put("app.request-metrics.enabled", "true");
        properties.put("app.request-metrics.port", Integer.toString(METRICS_PORT));
        properties.put("endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
        return properties;
    }

    private HttpAnswer scrapeAnswer() {
        return external(METRICS_PORT).get("/metrics");
    }

    private EngineClient external(int port) {
        return new EngineClient(URI.create("http://localhost:" + port + "/"));
    }

    private String scrape() {
        HttpAnswer answer = scrapeAnswer();
        assertThat(answer.status()).isEqualTo(200);
        return answer.body();
    }

    @Test
    @DisplayName("GraphQL, REST and realtime traffic of a known project is scraped from the internal port")
    void scrapeShowsProjectSeries() throws Exception {
        assertThat(http.graphql("/" + PROJECT + "/graphql", "{ __typename }").status()).isEqualTo(200);
        HttpAnswer withErrors = http.graphql("/" + PROJECT + "/graphql", "{ noSuchField { id } }");
        assertThat(withErrors.status()).isEqualTo(200);
        assertThat(withErrors.body()).contains("errors");
        assertThat(http.get("/" + PROJECT + "/api/v1/products").status()).isEqualTo(200);
        assertThat(http.get("/unknown-proj/api/v1/products").status()).isEqualTo(404);

        String scrapeWhileOpen;
        try (WsClient session = subscribe()) {
            subscriptionService.publish(null, new CDCEvent("INSERT", "public", "products", "{\"id\":99}", 0L));
            assertThat(session.poll(Duration.ofSeconds(5))).contains("\"next\"");
            scrapeWhileOpen = scrape();
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

        HttpAnswer actuatorMetrics = external(MANAGEMENT_PORT).get("/actuator/metrics");
        assertThat(actuatorMetrics.status()).isEqualTo(200);
        assertThat(actuatorMetrics.body()).contains("jvm.memory.used").doesNotContain("excalibase.requests");
        assertThat(external(MANAGEMENT_PORT).get("/actuator/metrics/excalibase.requests").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the internal port serves only GET /metrics, the public port never serves the scrape")
    void portsAreSeparate() {
        HttpAnswer internalScrape = scrapeAnswer();
        assertThat(internalScrape.header("Content-Type")).startsWith("text/plain").contains("version=0.0.4");

        for (String path : List.of("/" + PROJECT + "/api/v1/products", "/actuator/health", "/metrics/x")) {
            HttpAnswer refused = external(METRICS_PORT).get(path);
            assertThat(refused.status()).as(path).isEqualTo(404);
            assertThat(refused.body()).as(path).isEmpty();
        }
        assertThat(http.get("/metrics").body()).doesNotContain("excalibase_requests");
        for (String path : List.of("/actuator/health", "/actuator/metrics", "/actuator/prometheus")) {
            assertThat(http.get(path).status()).as("main port " + path).isEqualTo(404);
        }
    }

    private WsClient subscribe() throws Exception {
        WsClient session = WsClient.open(URI.create("ws://localhost:" + server.getPort() + "/" + PROJECT + "/graphql"),
                null, List.of("graphql-transport-ws"));
        session.send("{\"type\":\"connection_init\"}");
        assertThat(session.poll(Duration.ofSeconds(5))).contains("connection_ack");
        session.send("{\"type\":\"subscribe\",\"id\":\"s1\",\"payload\":"
                + "{\"query\":\"subscription { publicProductsChanges { operation } }\"}}");
        await().atMost(Duration.ofSeconds(5)).until(() -> subscriptionService.hasSubscribers(null, "public.products"));
        return session;
    }
}
