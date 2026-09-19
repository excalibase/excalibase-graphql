package io.github.excalibase.cors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProvisioningProjectCorsProvider}: it reads a project's
 * browser-origin allowlist from {@code GET {base}/projects/{id}/info}, caches it
 * per project with a TTL, serves the last good copy while provisioning is down,
 * and fails CLOSED (throws) when nothing is cached — it must never invent an
 * allowlist.
 */
class ProvisioningProjectCorsProviderTest {

    private static final String PROJECT = "proj1";
    private static final String INFO_PATH = "/api/projects/" + PROJECT + "/info";
    private static final String ORIGIN_A = "https://app.example.com";
    private static final String ORIGIN_B = "http://localhost:5173";

    private static final String INFO_BODY = """
            {
              "projectId": "proj1",
              "projectName": "web",
              "orgId": "org1",
              "orgSlug": "acme",
              "orgName": "Acme",
              "realtimeAutoEnable": true,
              "corsAllowedOrigins": ["http://localhost:5173", "https://app.example.com"]
            }
            """;

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String body = INFO_BODY;
    private volatile String authHeaderSeen;
    private final long[] now = {1_000L};

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext(INFO_PATH, exchange -> {
            hits.incrementAndGet();
            authHeaderSeen = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, body);
        });
        server.createContext("/api/projects/unknown/info", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    private void respond(HttpExchange exchange, String payload) throws IOException {
        if (responseStatus != 200) {
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
            return;
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private ProvisioningProjectCorsProvider provider(long ttlMillis) {
        return new ProvisioningProjectCorsProvider(
                "http://localhost:" + port + "/api/", "test-pat", ttlMillis, () -> now[0]);
    }

    @Test
    @DisplayName("maps corsAllowedOrigins from the /info payload")
    void mapsOrigins() {
        assertThat(provider(60_000).originsFor(PROJECT)).containsExactly(ORIGIN_B, ORIGIN_A);
    }

    @Test
    @DisplayName("sends the service PAT as a Bearer token")
    void sendsBearerToken() {
        provider(60_000).originsFor(PROJECT);
        assertThat(authHeaderSeen).isEqualTo("Bearer test-pat");
    }

    @Test
    @DisplayName("a missing or null corsAllowedOrigins field reads as no origins, not as an error")
    void missingFieldIsEmpty() {
        body = "{\"projectId\":\"proj1\"}";
        assertThat(provider(60_000).originsFor(PROJECT)).isEmpty();
        body = "{\"projectId\":\"proj1\",\"corsAllowedOrigins\":null}";
        assertThat(provider(60_000).originsFor(PROJECT)).isEmpty();
    }

    @Test
    @DisplayName("caches within TTL — a second read does not re-hit provisioning")
    void cachesWithinTtl() {
        ProvisioningProjectCorsProvider p = provider(30_000);
        p.originsFor(PROJECT);
        now[0] += 29_999;
        p.originsFor(PROJECT);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-fetches after the TTL expires")
    void refetchesAfterTtl() {
        ProvisioningProjectCorsProvider p = provider(30_000);
        p.originsFor(PROJECT);
        now[0] += 30_001;
        p.originsFor(PROJECT);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("evict drops the cached list so the next read re-fetches")
    void evictForcesRefetch() {
        ProvisioningProjectCorsProvider p = provider(60_000);
        p.originsFor(PROJECT);
        p.evict(PROJECT);
        p.originsFor(PROJECT);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fail-closed: provisioning down + nothing cached → throws (never an empty or wildcard list)")
    void failsClosedWhenDownAndNoCache() {
        responseStatus = 503;
        ProvisioningProjectCorsProvider p = provider(60_000);
        assertThatThrownBy(() -> p.originsFor(PROJECT)).isInstanceOf(CorsOriginsFetchException.class);
    }

    @Test
    @DisplayName("fail-closed on a network error (connection refused)")
    void failsClosedOnNetworkError() {
        server.stop(0);
        server = null;
        ProvisioningProjectCorsProvider p = provider(60_000);
        assertThatThrownBy(() -> p.originsFor(PROJECT)).isInstanceOf(CorsOriginsFetchException.class);
    }

    @Test
    @DisplayName("an unknown project (404) is a fetch failure, not an empty allowlist")
    void unknownProjectThrows() {
        ProvisioningProjectCorsProvider p = provider(60_000);
        assertThatThrownBy(() -> p.originsFor("unknown")).isInstanceOf(CorsOriginsFetchException.class);
    }

    @Test
    @DisplayName("stale-while-error: serves the last good list if provisioning goes down after a successful fetch")
    void servesStaleOnErrorAfterSuccess() {
        ProvisioningProjectCorsProvider p = provider(30_000);
        p.originsFor(PROJECT);
        responseStatus = 503;
        now[0] += 30_001;

        List<String> stale = p.originsFor(PROJECT);
        assertThat(stale).containsExactly(ORIGIN_B, ORIGIN_A);
    }

    @Test
    @DisplayName("a malformed payload is a fetch failure")
    void malformedPayloadThrows() {
        body = "not json";
        ProvisioningProjectCorsProvider p = provider(60_000);
        assertThatThrownBy(() -> p.originsFor(PROJECT)).isInstanceOf(CorsOriginsFetchException.class);
    }

    private ProvisioningProjectCorsProvider providerWithTokenSource(TokenFileSource tokenSource) {
        return new ProvisioningProjectCorsProvider(
                "http://localhost:" + port + "/api/", tokenSource, 60_000, () -> now[0]);
    }

    @Test
    @DisplayName("Authorization header is built from the token file contents")
    void authorizationHeaderComesFromTokenFile(@TempDir Path dir) throws IOException {
        Path tokenFile = Files.writeString(dir.resolve("graphql-token"), "file-pat\n");

        providerWithTokenSource(new TokenFileSource(tokenFile.toString(), "unused-literal", () -> now[0]))
                .originsFor(PROJECT);

        assertThat(authHeaderSeen).isEqualTo("Bearer file-pat");
    }

    @Test
    @DisplayName("a rotated token file is picked up without reconstructing the provider")
    void picksUpRotatedTokenFile(@TempDir Path dir) throws IOException {
        Path tokenFile = Files.writeString(dir.resolve("graphql-token"), "old-pat\n");
        ProvisioningProjectCorsProvider provider =
                providerWithTokenSource(new TokenFileSource(tokenFile.toString(), "unused-literal", () -> now[0]));
        provider.originsFor(PROJECT);
        assertThat(authHeaderSeen).isEqualTo("Bearer old-pat");

        Files.writeString(tokenFile, "rotated-pat\n");
        now[0] += 6_000L;
        provider.evict(PROJECT);
        provider.originsFor(PROJECT);

        assertThat(authHeaderSeen).isEqualTo("Bearer rotated-pat");
    }

    @Test
    @DisplayName("an unresolvable token fails explicitly and sends no request at all")
    void unresolvableTokenFailsWithoutCallingProvisioning(@TempDir Path dir) {
        TokenFileSource empty = new TokenFileSource(dir.resolve("absent").toString(), "", () -> now[0]);
        ProvisioningProjectCorsProvider provider = providerWithTokenSource(empty);

        assertThatThrownBy(() -> provider.originsFor(PROJECT))
                .isInstanceOf(CorsOriginsFetchException.class)
                .hasRootCauseInstanceOf(TokenUnavailableException.class);
        assertThat(hits.get()).isZero();
        assertThat(authHeaderSeen).isNull();
    }
}
