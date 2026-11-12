package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OverloadDisabledIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();

    @Inject
    ApplicationContext context;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.jwt-enabled", "false",
                "app.security.insecure-dev-mode", "true",
                "app.overload.enabled", "false",
                "endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
    }

    @Test
    @DisplayName("with app.overload.enabled=false nothing is registered")
    void nothingRegistered() {
        assertThat(context.containsBean(ConnectionBound.class)).isFalse();
        assertThat(context.containsBean(ConnectionGate.class)).isFalse();
    }

    @Test
    @DisplayName("with app.overload.enabled=false the bound is not reported")
    void noBoundMetric() throws Exception {
        HttpResponse<String> max = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + MANAGEMENT_PORT + "/actuator/metrics/tomcat.connections.config.max")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(max.statusCode()).isEqualTo(404);
    }
}
