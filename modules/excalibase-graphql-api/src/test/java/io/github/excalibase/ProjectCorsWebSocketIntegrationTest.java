package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.StubControlPlane;
import io.github.excalibase.support.WsClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The realtime and GraphQL WebSockets on a real server: browsers do not apply
 * CORS to WebSockets, so the upgrade itself refuses a web origin that is not
 * on the project's allowlist and admits listed origins, native-app schemes and
 * clients that send no Origin.
 */
class ProjectCorsWebSocketIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-cors-ws";
    private static final String ALLOWED = "https://app.example.com";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    static final StubControlPlane provisioning = new StubControlPlane()
            .serveJson("/api/projects/" + PROJECT + "/info",
                    "{\"projectId\":\"" + PROJECT + "\",\"corsAllowedOrigins\":[\"" + ALLOWED + "\"]}")
            .start();

    @AfterAll
    static void teardown() {
        provisioning.close();
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, PROJECT));
        properties.put("app.allowed-schema", "test_schema");
        properties.put("app.nats.enabled", "false");
        properties.put("app.cors.provisioning-url", provisioning.url("/api"));
        properties.put("app.security.multi-tenant.provisioning-pat", "test-pat");
        return properties;
    }

    private WsClient open(String path, String origin) throws Exception {
        return WsClient.open(URI.create("ws://localhost:" + server.getPort() + path), origin, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/" + PROJECT + "/api/v1/realtime", "/" + PROJECT + "/graphql"})
    @DisplayName("an unlisted web origin cannot open the socket")
    void unlistedWebOriginIsRefused(String path) {
        assertThatThrownBy(() -> open(path, "https://evil.example"))
                .isInstanceOf(ExecutionException.class)
                .satisfies(failure -> assertThat(WsClient.refusedStatus(failure)).isEqualTo(403));
    }

    @ParameterizedTest
    @ValueSource(strings = {ALLOWED, "capacitor://localhost", "tauri://localhost", "null", ""})
    @DisplayName("a listed origin, a native-app origin and no Origin all open the socket")
    void admittedOriginsConnect(String origin) throws Exception {
        try (WsClient session = open("/" + PROJECT + "/api/v1/realtime", origin.isEmpty() ? null : origin)) {
            assertThat(session.isOpen()).isTrue();
        }
    }
}
