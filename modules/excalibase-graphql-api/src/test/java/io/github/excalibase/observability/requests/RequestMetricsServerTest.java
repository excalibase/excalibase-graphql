package io.github.excalibase.observability.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestMetricsServerTest {

    private final RequestMetrics metrics = new RequestMetrics(10);
    private RequestMetricsServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> call(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the internal port answers GET /metrics with the scrape")
    void servesScrape() throws Exception {
        metrics.recordRequest("proj-a", RequestFamily.GRAPHQL, RequestOutcome.OK, Duration.ZERO);
        server = new RequestMetricsServer(metrics, 0, "127.0.0.1");

        HttpResponse<String> response = call("GET", "/metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain").contains("version=0.0.4");
        assertThat(response.body()).contains("excalibase_requests_seconds_count{");
    }

    @Test
    @DisplayName("anything else on the internal port is 404")
    void refusesOtherRequests() throws Exception {
        server = new RequestMetricsServer(metrics, 0, "127.0.0.1");

        assertThat(call("GET", "/proj-a/graphql").statusCode()).isEqualTo(404);
        assertThat(call("POST", "/metrics").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/metrics/x").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("no address: the internal port listens on every interface, as Kubernetes runs it")
    void noAddressBindsEveryInterface() {
        server = new RequestMetricsServer(metrics, 0, " ");

        assertThat(server.boundAddress().isAnyLocalAddress()).isTrue();
    }

    @Test
    @DisplayName("an address: the internal port binds it alone, so a single host keeps it off tenant networks")
    void addressBindsOnlyThatAddress() {
        server = new RequestMetricsServer(metrics, 0, " 127.0.0.1 ");

        assertThat(server.boundAddress().getHostAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("an address that does not resolve stops the start instead of binding every interface")
    void unresolvableAddressRefusesToStart() {
        assertThatThrownBy(() -> new RequestMetricsServer(metrics, 0, "no-such-host.invalid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-host.invalid");
    }
}
