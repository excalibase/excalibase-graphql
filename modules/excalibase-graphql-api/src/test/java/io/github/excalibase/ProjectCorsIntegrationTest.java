package io.github.excalibase;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that CORS on the data plane is decided per project from the
 * allowlist provisioning serves on {@code /api/projects/{id}/info}: an allowed
 * origin is echoed on GraphQL, REST and the WebSocket upgrade; a foreign origin,
 * a project with no origins and an unknown project are refused; non-project
 * routes keep the platform default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProjectCorsIntegrationTest {

    private static final String PROJECT = "proj-cors";
    private static final String LOCKED_PROJECT = "proj-locked";
    private static final String ALLOWED = "https://app.example.com";
    private static final String OTHER = "https://evil.example";
    private static final String ALLOW_ORIGIN = HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
    private static final String TYPENAME_QUERY = "{\"query\":\"{ __typename }\"}";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    private static final String FILE_TOKEN = "file-delivered-pat";

    static HttpServer stub;
    static int stubPort;
    static Path tokenFile;
    static volatile String authHeaderSeen;

    static {
        try {
            tokenFile = Files.createTempFile("provisioning-token", ".txt");
            tokenFile.toFile().deleteOnExit();
            Files.writeString(tokenFile, FILE_TOKEN + "\n");
            stub = HttpServer.create(new InetSocketAddress(0), 0);
            stubPort = stub.getAddress().getPort();
            serve("/api/projects/" + PROJECT + "/info",
                    "{\"projectId\":\"" + PROJECT + "\",\"corsAllowedOrigins\":[\"" + ALLOWED + "\"]}");
            serve("/api/projects/" + LOCKED_PROJECT + "/info",
                    "{\"projectId\":\"" + LOCKED_PROJECT + "\",\"corsAllowedOrigins\":[]}");
            stub.start();
        } catch (Exception e) {
            throw new RuntimeException("stub setup failed", e);
        }
    }

    private static void serve(String path, String body) {
        stub.createContext(path, exchange -> {
            authHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @AfterAll
    static void teardown() {
        if (stub != null) stub.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.allowed-schema", () -> "test_schema");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.cors.allowed-origins", () -> "*");
        registry.add("app.cors.provisioning-url", () -> "http://localhost:" + stubPort + "/api");
        // Token file only, exactly as the platform mounts it: the CORS provider must
        // read the shared provisioning token source, not a CORS-specific literal.
        registry.add("app.security.multi-tenant.provisioning-pat-file", tokenFile::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GraphQL: an origin on the project's allowlist is echoed")
    void graphqlAllowedOrigin() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    @DisplayName("GraphQL: an origin not on the allowlist is refused without CORS headers")
    void graphqlDeniedOrigin() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("preflight follows the project's allowlist")
    void preflight() throws Exception {
        mockMvc.perform(options("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));

        mockMvc.perform(options("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("a project with no origins blocks every browser origin")
    void projectWithoutOriginsDeniesAll() throws Exception {
        mockMvc.perform(post("/" + LOCKED_PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("an unknown project is denied — never the platform default")
    void unknownProjectDenied() throws Exception {
        mockMvc.perform(post("/proj-does-not-exist/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("REST: the same allowlist governs /{projectId}/api/v1")
    void restFollowsProjectAllowlist() throws Exception {
        mockMvc.perform(get("/" + PROJECT + "/api/v1/customer")
                        .header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED));

        mockMvc.perform(get("/" + PROJECT + "/api/v1/customer")
                        .header(HttpHeaders.ORIGIN, OTHER))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("WebSocket upgrade: the handshake is origin-checked against the project's allowlist")
    void websocketHandshakeFollowsProjectAllowlist() throws Exception {
        mockMvc.perform(get("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header(HttpHeaders.CONNECTION, "Upgrade"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));

        mockMvc.perform(get("/" + PROJECT + "/api/v1/realtime")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header(HttpHeaders.CONNECTION, "Upgrade"))
                .andExpect(status().isForbidden());

        // An allowed origin reaches the handshake handler (which then fails on the
        // missing Sec-WebSocket-* headers MockMvc cannot supply); what matters is
        // that the CORS layer let it through with the origin echoed.
        mockMvc.perform(get("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.UPGRADE, "websocket")
                        .header(HttpHeaders.CONNECTION, "Upgrade"))
                .andExpect(header().string(ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    @DisplayName("the /info call authenticates with the mounted token file")
    void infoCallUsesTheMountedTokenFile() throws Exception {
        mockMvc.perform(post("/" + PROJECT + "/graphql")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isOk());

        assertThat(authHeaderSeen).isEqualTo("Bearer " + FILE_TOKEN);
    }

    @Test
    @DisplayName("non-browser clients (no Origin) are unaffected")
    void noOriginIsNotCors() throws Exception {
        mockMvc.perform(post("/" + LOCKED_PROJECT + "/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TYPENAME_QUERY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("non-project routes keep the platform default")
    void platformRoutesKeepGlobalDefault() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, OTHER))
                .andExpect(header().string(ALLOW_ORIGIN, "*"));
    }
}
