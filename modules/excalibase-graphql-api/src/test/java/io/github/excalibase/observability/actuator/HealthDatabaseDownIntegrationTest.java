package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A configured database nobody answers on: health is DOWN with 503, the probes (off here) stay absent. */
class HealthDatabaseDownIntegrationTest extends EngineIntegrationTest {

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
                "endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
    }

    @Test
    @DisplayName("an unreachable database turns health DOWN with 503 and a status-only body")
    void databaseDown() {
        HttpAnswer answer = management.get("/actuator/health");

        assertThat(answer.status()).isEqualTo(503);
        assertThat(answer.header("Content-Type")).isEqualTo("application/vnd.spring-boot.actuator.v3+json");
        assertThat(answer.body()).isEqualTo("{\"status\":\"DOWN\"}");
    }

    @Test
    @DisplayName("without the probes enabled their paths are bare 404s")
    void probesOff() {
        assertThat(management.get("/actuator/health/liveness").status()).isEqualTo(404);
        assertThat(management.get("/actuator/health/readiness").body()).isEmpty();
    }
}
