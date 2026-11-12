package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.permissions.ProvisioningPermissionProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A control-plane outage over real HTTP (docs/features/permissions.md §8): a user is served the cached
 * permissions for the max-stale window, then refused with 503 {@code permissions_unavailable}, while a
 * service token keeps being served; a successful refresh restores the user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PermissionsOutageIntegrationTest {

    private static final String PROJECT = "proj-outage";
    private static final String ALICE = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ROLE = "user";
    private static final long TTL_MS = 300;
    private static final long MAX_STALE_MS = 1_500;
    private static final long RETRY_INTERVAL_MS = 1_000;
    private static final long PATIENCE_MS = 10_000;
    private static final String DOCS_QUERY = "{\"query\":\"{ rlsDemoDocs { id owner_id } }\"}";

    private static final String PERMISSIONS = PermissionDocs.document(PROJECT,
            PermissionDocs.entry("rls_demo.docs", USER_ROLE, PermissionDocs.select(PermissionDocs.OWNED, "\"*\"")));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-engine-rls.sql");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ECPrivateKey PRIVATE_KEY;
    private static final HttpServer JWKS;
    private static final ControlPlaneStub CONTROL_PLANE;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = generator.generateKeyPair();
            PRIVATE_KEY = (ECPrivateKey) keyPair.getPrivate();
            JWKS = jwksServer((ECPublicKey) keyPair.getPublic());
            CONTROL_PLANE = new ControlPlaneStub();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("stub setup failed", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.security.jwt-enabled", () -> "true");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.auth.jwks-url",
                () -> "http://localhost:" + JWKS.getAddress().getPort() + "/.well-known/jwks.json");
        registry.add("app.security.rls.policy-url", () -> "http://localhost:" + CONTROL_PLANE.port + "/api");
        registry.add("app.security.rls.policy-pat", () -> "test-pat");
        registry.add("app.security.rls.policy-ttl-ms", () -> TTL_MS);
        registry.add("app.security.permissions.max-stale-ms", () -> MAX_STALE_MS);
        registry.add("app.security.permissions.retry-interval-ms", () -> RETRY_INTERVAL_MS);
    }

    @AfterAll
    static void stopStubs() {
        CONTROL_PLANE.stop();
        JWKS.stop(0);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void controlPlaneIsUp() throws Exception {
        CONTROL_PLANE.serve();
        awaitGraphqlStatus(userToken(), 200);
    }

    @Test
    void aControlPlaneAnswering500_expiresTheUserAfterTheWindow_andServiceIsStillServed() throws Exception {
        assertThat(rowCount(graphql(userToken()))).isEqualTo(2);

        CONTROL_PLANE.fail();
        long outageStartedAt = System.currentTimeMillis();
        Thread.sleep(TTL_MS + 100);
        HttpResponse<String> withinWindow = graphql(userToken());
        assertThat(withinWindow.statusCode()).isEqualTo(200);
        assertThat(rowCount(withinWindow)).isEqualTo(2);
        assertThat(staleSeconds()).isGreaterThanOrEqualTo(0);

        HttpResponse<String> expired = awaitGraphqlStatus(userToken(), 503);
        assertThat(System.currentTimeMillis() - outageStartedAt).isGreaterThanOrEqualTo(MAX_STALE_MS);
        assertThat(errorCode(expired)).isEqualTo("permissions_unavailable");
        assertThat(rest(userToken()).statusCode()).isEqualTo(503);
        assertThat(staleSeconds()).isGreaterThanOrEqualTo(MAX_STALE_MS / 1000.0);

        HttpResponse<String> service = graphql(serviceToken());
        assertThat(service.statusCode()).isEqualTo(200);
        assertThat(rowCount(service)).isEqualTo(3);

        CONTROL_PLANE.serve();
        HttpResponse<String> recovered = awaitGraphqlStatus(userToken(), 200);
        assertThat(rowCount(recovered)).isEqualTo(2);
        assertThat(staleSeconds()).isZero();
    }

    @Test
    void aControlPlaneRefusingConnections_expiresTheUserAfterTheWindow_andServiceIsStillServed() throws Exception {
        CONTROL_PLANE.refuseConnections();
        Thread.sleep(TTL_MS + 100);
        assertThat(graphql(userToken()).statusCode()).isEqualTo(200);

        HttpResponse<String> expired = awaitGraphqlStatus(userToken(), 503);
        assertThat(errorCode(expired)).isEqualTo("permissions_unavailable");
        assertThat(graphql(serviceToken()).statusCode()).isEqualTo(200);

        CONTROL_PLANE.serve();
        assertThat(rowCount(awaitGraphqlStatus(userToken(), 200))).isEqualTo(2);
    }

    @Test
    void oneRequest_readsThePermissionsOnce_onGraphqlAndRest() throws Exception {
        Thread.sleep(TTL_MS + 100);
        int before = CONTROL_PLANE.hits.get();
        assertThat(graphql(userToken()).statusCode()).isEqualTo(200);
        assertThat(CONTROL_PLANE.hits.get()).isEqualTo(before + 1);

        Thread.sleep(TTL_MS + 100);
        before = CONTROL_PLANE.hits.get();
        assertThat(rest(userToken()).statusCode()).isEqualTo(200);
        assertThat(CONTROL_PLANE.hits.get()).isEqualTo(before + 1);
    }

    @Test
    void duringAnOutage_requestsBetweenAttemptsDoNotReachTheControlPlane() throws Exception {
        CONTROL_PLANE.fail();
        Thread.sleep(TTL_MS + 100);
        int before = CONTROL_PLANE.hits.get();
        for (int request = 0; request < 5; request++) {
            graphql(userToken());
        }

        assertThat(CONTROL_PLANE.hits.get() - before).isEqualTo(1);
    }

    private HttpResponse<String> awaitGraphqlStatus(String token, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + PATIENCE_MS;
        HttpResponse<String> response = graphql(token);
        while (response.statusCode() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            response = graphql(token);
        }
        assertThat(response.statusCode()).as("status within %d ms: %s", PATIENCE_MS, response.body())
                .isEqualTo(expected);
        return response;
    }

    private HttpResponse<String> graphql(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + PROJECT + "/graphql"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(DOCS_QUERY))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rest(String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + PROJECT + "/api/v1/docs"))
                .header("Authorization", "Bearer " + token)
                .header("Accept-Profile", "rls_demo")
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int rowCount(HttpResponse<String> response) throws IOException {
        JsonNode rows = MAPPER.readTree(response.body()).path("data").path("rlsDemoDocs");
        assertThat(rows.isArray()).as("rows in %s", response.body()).isTrue();
        return rows.size();
    }

    private static String errorCode(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body()).path("errors").path(0).path("extensions").path("code").asText();
    }

    private double staleSeconds() {
        Gauge gauge = meters.find(ProvisioningPermissionProvider.STALE_SECONDS_METRIC).tag("project", PROJECT).gauge();
        assertThat(gauge).as("staleness gauge for %s", PROJECT).isNotNull();
        return gauge.value();
    }

    private static String userToken() throws JOSEException {
        return sign(Map.of("userId", ALICE, "role", USER_ROLE));
    }

    private static String serviceToken() throws JOSEException {
        return sign(Map.of("role", "service", "scope", "service"));
    }

    private static String sign(Map<String, Object> claims) throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .subject("alice@test.com")
                .claim("projectId", PROJECT)
                .audience("excalibase:" + PROJECT)
                .issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z")));
        claims.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), builder.build());
        signed.sign(new ECDSASigner(PRIVATE_KEY));
        return signed.serialize();
    }

    private static HttpServer jwksServer(ECPublicKey publicKey) throws IOException {
        String jwks = new JWKSet(new ECKey.Builder(Curve.P_256, publicKey)
                .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> respond(exchange, 200, jwks));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** The control plane's permissions endpoint on a fixed port: serving, answering 500, or not listening. */
    private static final class ControlPlaneStub {

        private final int port;
        private final AtomicInteger hits = new AtomicInteger();
        private volatile HttpServer server;
        private volatile boolean failing;

        ControlPlaneStub() throws IOException {
            server = listen(0);
            port = server.getAddress().getPort();
        }

        synchronized void serve() {
            failing = false;
            if (server == null) {
                server = listen(port);
            }
        }

        synchronized void fail() {
            failing = true;
            if (server == null) {
                server = listen(port);
            }
        }

        synchronized void refuseConnections() {
            stop();
        }

        synchronized void stop() {
            if (server != null) {
                server.stop(0);
                server = null;
            }
        }

        private HttpServer listen(int onPort) {
            try {
                HttpServer created = HttpServer.create(new InetSocketAddress(onPort), 0);
                created.createContext("/api/provision/" + PROJECT + "/permissions/", exchange -> {
                    hits.incrementAndGet();
                    if (failing) {
                        respond(exchange, 500, "{\"error\":\"down\"}");
                    } else {
                        respond(exchange, 200, PERMISSIONS);
                    }
                });
                created.start();
                return created;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot listen on port " + onPort, e);
            }
        }
    }
}
