package io.github.excalibase;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The realtime and GraphQL WebSockets on a real server: browsers do not apply
 * CORS to WebSockets, so the upgrade itself refuses a web origin that is not
 * on the project's allowlist and admits listed origins, native-app schemes and
 * clients that send no Origin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ProjectCorsWebSocketIntegrationTest {

    private static final String PROJECT = "proj-cors-ws";
    private static final String ALLOWED = "https://app.example.com";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    static final HttpServer stub = startProvisioningStub();

    private static HttpServer startProvisioningStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            byte[] body = ("{\"projectId\":\"" + PROJECT + "\",\"corsAllowedOrigins\":[\"" + ALLOWED + "\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/api/projects/" + PROJECT + "/info", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("provisioning stub failed to start", e);
        }
    }

    @AfterAll
    static void teardown() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.allowed-schema", () -> "test_schema");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.nats.enabled", () -> false);
        registry.add("app.cors.provisioning-url", () -> "http://localhost:" + stub.getAddress().getPort() + "/api");
        registry.add("app.security.multi-tenant.provisioning-pat", () -> "test-pat");
    }

    @LocalServerPort
    private int port;

    private WebSocketSession open(String path, String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), headers, URI.create("ws://localhost:" + port + path))
                .get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/" + PROJECT + "/api/v1/realtime", "/" + PROJECT + "/graphql"})
    @DisplayName("an unlisted web origin cannot open the socket")
    void unlistedWebOriginIsRefused(String path) {
        assertThatThrownBy(() -> open(path, "https://evil.example"))
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining("403");
    }

    @ParameterizedTest
    @ValueSource(strings = {ALLOWED, "capacitor://localhost", "tauri://localhost", "null", ""})
    @DisplayName("a listed origin, a native-app origin and no Origin all open the socket")
    void admittedOriginsConnect(String origin) throws Exception {
        WebSocketSession session = open("/" + PROJECT + "/api/v1/realtime", origin.isEmpty() ? null : origin);
        try {
            assertThat(session.isOpen()).isTrue();
        } finally {
            session.close();
        }
    }
}
