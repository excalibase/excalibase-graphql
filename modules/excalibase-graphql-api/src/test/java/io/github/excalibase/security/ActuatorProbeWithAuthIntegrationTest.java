package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Kubernetes probe, on a real server with authentication on: the management port answers it with no token, and the
 * same path on the public port is the project 404.
 */
class ActuatorProbeWithAuthIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();

    private final EngineClient management = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/"));

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.server.port", "-1");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.multi-tenant.provisioning-url", "http://localhost:59998/api");
        properties.put("app.security.auth.hmac-secret", "0123456789abcdef0123456789abcdef0123456789");
        properties.put("endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
        return properties;
    }

    @Test
    @DisplayName("with jwt on, the probe on the management port needs no token")
    void probeOnTheManagementPort() {
        HttpAnswer answer = management.get("/actuator/health");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("with jwt on, the same path on the public port is the project 404")
    void probePathOnThePublicPort() {
        HttpAnswer answer = http.get("/actuator/health");

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).contains("project_not_found");
    }
}
