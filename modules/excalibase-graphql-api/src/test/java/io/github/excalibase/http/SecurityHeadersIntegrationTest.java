package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every answer from the app carries the hardening headers the servlet stack's security chain added. */
class SecurityHeadersIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final List<String> VARY = List.of("Origin", "Access-Control-Request-Method",
            "Access-Control-Request-Headers");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
        return properties;
    }

    private static void assertHardened(HttpAnswer answer) {
        assertThat(answer.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(answer.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(answer.header("X-XSS-Protection")).isEqualTo("0");
        assertThat(answer.header("Cache-Control")).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(answer.header("Pragma")).isEqualTo("no-cache");
        assertThat(answer.header("Expires")).isEqualTo("0");
        assertThat(answer.headers().allValues("Vary")).containsExactlyElementsOf(VARY);
    }

    @Test
    @DisplayName("a refusal from a filter-less path, a handler answer and a routing error are all hardened")
    void publicPort() {
        assertHardened(http.get("/nothing"));
        assertHardened(http.postJson("/" + PROJECT + "/graphql", Map.of("query", "")));
        assertHardened(http.get("/" + PROJECT + "/graphql"));
    }

    @Test
    @DisplayName("a refusal the project filter writes itself (unknown project) is hardened too")
    void projectFilterRefusal() {
        HttpAnswer answer = http.get("/not-" + PROJECT + "/graphql");

        assertThat(answer.status()).isEqualTo(404);
        assertHardened(answer);
    }

    @Test
    @DisplayName("a request that sends an Origin still has each Vary value once")
    void varyWithOrigin() {
        HttpAnswer answer = http.get("/nothing", "Origin", "https://app.example.test");

        assertThat(answer.headers().allValues("Vary")).containsExactlyElementsOf(VARY);
    }

    @Test
    @DisplayName("the management port is hardened too")
    void managementPort() {
        EngineClient management = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/"));

        assertHardened(management.get("/actuator/nothing"));
    }
}
