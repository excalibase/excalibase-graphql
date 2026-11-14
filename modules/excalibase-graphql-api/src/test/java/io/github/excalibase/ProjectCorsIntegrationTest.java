package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.StubControlPlane;
import io.github.excalibase.support.WsClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end proof that CORS on the data plane is decided per project from the
 * allowlist provisioning serves on {@code /api/projects/{id}/info}: an allowed
 * origin is echoed on GraphQL and REST; an unlisted origin's request is served
 * without a grant (the browser blocks it) and its preflight refused; the
 * WebSocket upgrade, which browsers do not guard, refuses unlisted web origins
 * itself; non-project routes keep the platform default.
 */
class ProjectCorsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-cors";
    private static final String LOCKED_PROJECT = "proj-locked";
    private static final String ALLOWED = "https://app.example.com";
    private static final String OTHER = "https://evil.example";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String TYPENAME_QUERY = "{ __typename }";
    private static final String FILE_TOKEN = "file-delivered-pat";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    static final Path tokenFile = writeTokenFile();

    static final StubControlPlane provisioning = new StubControlPlane()
            .serveJson("/api/projects/" + PROJECT + "/info",
                    "{\"projectId\":\"" + PROJECT + "\",\"corsAllowedOrigins\":[\"" + ALLOWED + "\"]}")
            .serveJson("/api/projects/" + LOCKED_PROJECT + "/info",
                    "{\"projectId\":\"" + LOCKED_PROJECT + "\",\"corsAllowedOrigins\":[]}")
            .start();

    private static Path writeTokenFile() {
        try {
            Path file = Files.createTempFile("provisioning-token", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, FILE_TOKEN + "\n");
            return file;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @AfterAll
    static void teardown() {
        provisioning.close();
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.postgres(postgres, PROJECT));
        properties.put("app.allowed-schema", "test_schema");
        properties.put("app.cors.allowed-origins", "*");
        properties.put("app.cors.provisioning-url", provisioning.url("/api"));
        // Token file only, exactly as the platform mounts it: the CORS provider must
        // read the shared provisioning token source, not a CORS-specific literal.
        properties.put("app.security.multi-tenant.provisioning-pat-file", tokenFile.toString());
        return properties;
    }

    private HttpAnswer graphqlFrom(String project, String origin) {
        return origin == null ? http.graphql("/" + project + "/graphql", TYPENAME_QUERY)
                : http.graphql("/" + project + "/graphql", TYPENAME_QUERY, "Origin", origin);
    }

    private static void assertVariesOnOrigin(HttpAnswer answer) {
        assertThat(answer.headers().allValues("Vary")).contains("Origin");
    }

    @Test
    @DisplayName("GraphQL: an origin on the project's allowlist is echoed")
    void graphqlAllowedOrigin() {
        HttpAnswer answer = graphqlFrom(PROJECT, ALLOWED);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header(ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(answer.header("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    @DisplayName("GraphQL: an unlisted origin is served but granted nothing, so a browser cannot read it")
    void graphqlUnlistedOriginServedWithoutGrant() {
        HttpAnswer answer = graphqlFrom(PROJECT, OTHER);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header(ALLOW_ORIGIN)).isNull();
        assertVariesOnOrigin(answer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacitor://localhost", "tauri://localhost", "null", "http://localhost:5173"})
    @DisplayName("GraphQL and REST: native-app and proxied origins are served, never refused for their Origin")
    void nativeAndProxiedOriginsAreServed(String origin) {
        HttpAnswer graphql = graphqlFrom(PROJECT, origin);
        assertThat(graphql.status()).isEqualTo(200);
        assertThat(graphql.header(ALLOW_ORIGIN)).isNull();

        HttpAnswer rest = http.get("/" + PROJECT + "/api/v1/customer", "Origin", origin);
        assertThat(rest.status()).isEqualTo(200);
        assertThat(rest.header(ALLOW_ORIGIN)).isNull();
    }

    @Test
    @DisplayName("preflight follows the project's allowlist")
    void preflight() {
        HttpAnswer listed = http.send("OPTIONS", "/" + PROJECT + "/graphql", null,
                "Origin", ALLOWED,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type");
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.header(ALLOW_ORIGIN)).isEqualTo(ALLOWED);
        assertThat(listed.header("Access-Control-Allow-Methods")).isNotNull();

        HttpAnswer unlisted = http.send("OPTIONS", "/" + PROJECT + "/graphql", null,
                "Origin", OTHER,
                "Access-Control-Request-Method", "POST");
        assertThat(unlisted.status()).isEqualTo(403);
        assertThat(unlisted.header(ALLOW_ORIGIN)).isNull();
    }

    // A project with an empty allowlist is covered by ProjectCorsConfigurationSourceTest:
    // a single-database deployment serves one project, so it has no second one to lock.

    @Test
    @DisplayName("an unknown project is a 404 before any CORS lookup, never the platform default")
    void unknownProjectDenied() {
        HttpAnswer answer = graphqlFrom("proj-does-not-exist", ALLOWED);

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.header(ALLOW_ORIGIN)).isNull();
    }

    @Test
    @DisplayName("REST: the same allowlist governs /{projectId}/api/v1")
    void restFollowsProjectAllowlist() {
        HttpAnswer listed = http.get("/" + PROJECT + "/api/v1/customer", "Origin", ALLOWED);
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.header(ALLOW_ORIGIN)).isEqualTo(ALLOWED);

        HttpAnswer unlisted = http.get("/" + PROJECT + "/api/v1/customer", "Origin", OTHER);
        assertThat(unlisted.status()).isEqualTo(200);
        assertThat(unlisted.header(ALLOW_ORIGIN)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/realtime"})
    @DisplayName("WebSocket upgrade: an unlisted web origin is refused at the handshake on both routes")
    void websocketRefusesUnlistedWebOrigin(String path) {
        assertThatThrownBy(() -> openSocket(path, OTHER))
                .isInstanceOf(ExecutionException.class)
                .satisfies(failure -> {
                    assertThat(WsClient.refusedStatus(failure)).isEqualTo(403);
                    assertThat(WsClient.refusal(failure).headers().firstValue(ALLOW_ORIGIN)).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {ALLOWED, "capacitor://localhost", "tauri://localhost", ""})
    @DisplayName("WebSocket upgrade: a listed web origin, native-app origins and no Origin pass the origin check")
    void websocketAdmitsListedNativeAndAbsentOrigins(String origin) throws Exception {
        String sent = origin.isEmpty() ? null : origin;
        try (WsClient realtime = openSocket("/" + PROJECT + "/api/v1/realtime", sent);
             WsClient graphql = openSocket("/" + PROJECT + "/graphql", sent)) {
            assertThat(realtime.isOpen()).isTrue();
            assertThat(graphql.isOpen()).isTrue();
        }
    }

    private WsClient openSocket(String path, String origin) throws Exception {
        return WsClient.open(URI.create("ws://localhost:" + server.getPort() + path), origin, List.of());
    }

    @Test
    @DisplayName("the /info call authenticates with the mounted token file")
    void infoCallUsesTheMountedTokenFile() {
        assertThat(graphqlFrom(PROJECT, ALLOWED).status()).isEqualTo(200);

        assertThat(provisioning.lastAuthorization()).isEqualTo("Bearer " + FILE_TOKEN);
    }

    @Test
    @DisplayName("non-browser clients (no Origin) are unaffected")
    void noOriginIsNotCors() {
        // This deployment serves PROJECT only, so the locked project is answered by
        // the project check (404), not refused by CORS (403).
        HttpAnswer answer = graphqlFrom(LOCKED_PROJECT, null);

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.header(ALLOW_ORIGIN)).isNull();
    }

    @Test
    @DisplayName("non-project routes keep the platform default")
    void platformRoutesKeepGlobalDefault() {
        assertThat(http.get("/actuator/health", "Origin", OTHER).header(ALLOW_ORIGIN)).isEqualTo("*");
    }
}
