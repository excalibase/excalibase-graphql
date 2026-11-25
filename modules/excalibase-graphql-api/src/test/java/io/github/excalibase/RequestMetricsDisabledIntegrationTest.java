package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excalibase.observability.requests.RequestMetrics;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Off by default: no recorder, no internal port, and project traffic is served as before. */
class RequestMetricsDisabledIntegrationTest extends EngineIntegrationTest {

    private static final int METRICS_PORT = FreePort.find();
    private static final int MANAGEMENT_PORT = FreePort.find();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    @Inject
    ApplicationContext context;

    @Inject
    MeterRegistry globalRegistry;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, "test-proj"));
        properties.put("app.nats.enabled", "false");
        properties.put("app.request-metrics.port", Integer.toString(METRICS_PORT));
        properties.put("endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
        return properties;
    }

    @Test
    @DisplayName("no recorder, no filter, no internal port, and no per-project series anywhere")
    void nothingWhenOff() {
        assertThat(http.get("/test-proj/api/v1/products").status()).isEqualTo(200);

        assertThat(context.containsBean(RequestMetrics.class)).isFalse();
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", METRICS_PORT), 2000);
            }
        }).isInstanceOf(ConnectException.class);
        assertThat(globalRegistry.find("excalibase.requests").meters()).isEmpty();
        assertThat(http.get("/metrics").body()).doesNotContain("excalibase_");
        assertThat(http.get("/actuator/metrics").status()).isEqualTo(404);
        HttpAnswer actuatorMetrics = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/")).get("/actuator/metrics");
        assertThat(actuatorMetrics.status()).isEqualTo(200);
        assertThat(actuatorMetrics.body()).contains("jvm.memory.used").doesNotContain("excalibase.requests");
    }
}
