package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** No management settings at all: health, info and metrics only, no probes, and the database check switched off. */
class DefaultExposureIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();

    private final EngineClient management = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/"));

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "micronaut.server.port", "-1",
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.jwt-enabled", "false",
                "app.security.insecure-dev-mode", "true",
                "management.health.db.enabled", "false",
                "endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
    }

    @Test
    @DisplayName("health is UP without the database check, with no groups when probes are off")
    void healthWithoutDatabaseCheck() {
        assertThat(management.get("/actuator/health").status()).isEqualTo(200);
        assertThat(management.get("/actuator/health").body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    @DisplayName("by default prometheus is not exposed, and the index does not list it")
    void prometheusNotExposed() {
        List<String> links = new ArrayList<>();
        management.get("/actuator").json().path("_links").fieldNames().forEachRemaining(links::add);

        assertThat(links).containsExactlyInAnyOrder("self", "health", "health-path", "info", "metrics",
                "metrics-requiredMetricName");
        assertThat(management.get("/actuator/prometheus").status()).isEqualTo(404);
        assertThat(management.get("/actuator/metrics").status()).isEqualTo(200);
    }
}
