package io.github.excalibase;

import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.observability.requests.RequestMetricsFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Off by default: no recorder, no internal port, and project traffic is served as before. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RequestMetricsDisabledIntegrationTest {

    private static final int METRICS_PORT = RequestMetricsIntegrationTest.freePort();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.nats.enabled", () -> false);
        registry.add("app.request-metrics.port", () -> METRICS_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry globalRegistry;

    @Test
    @DisplayName("no recorder, no filter, no internal port, and no per-project series anywhere")
    void nothingWhenOff() throws Exception {
        HttpResponse<String> response = get(port, "/test-proj/api/v1/products");
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(context.getBeanProvider(RequestMetrics.class).getIfAvailable()).isNull();
        assertThat(context.getServletContext().getFilterRegistration("requestMetricsFilter")).isNull();
        assertThat(context.getBeansOfType(FilterRegistrationBean.class).values())
                .noneMatch(registration -> registration.getFilter() instanceof RequestMetricsFilter);
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", METRICS_PORT), 2000);
            }
        }).isInstanceOf(ConnectException.class);
        assertThat(globalRegistry.find("excalibase.requests").meters()).isEmpty();
        assertThat(get(port, "/metrics").body()).doesNotContain("excalibase_");
        assertThat(get(port, "/actuator/prometheus").body()).doesNotContain("excalibase_requests");
    }

    private static HttpResponse<String> get(int targetPort, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
