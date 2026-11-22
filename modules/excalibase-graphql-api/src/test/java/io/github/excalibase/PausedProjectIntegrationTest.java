package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * A project whose database is not there is answered with a 503 and a stable code on every surface, never a
 * generic 500. Provisioning hands out working-looking credentials for a paused project, so the engine sees
 * only a database that refuses connections ({@code project_unavailable}); when provisioning says the project is
 * paused (423) it is {@code project_paused}. Neither answer carries a host, a port or a driver message. (EXC-812)
 */
class PausedProjectIntegrationTest extends EngineIntegrationTest {

    private static final String UNREACHABLE = "proj_down12345";
    private static final String PAUSED = "proj_paused12345";
    private static final HttpServer provisioning = startProvisioning();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static HttpServer startProvisioning() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String refused = "{\"host\":\"127.0.0.1\",\"port\":\"" + FreePort.find()
                    + "\",\"database\":\"app\",\"username\":\"app\",\"password\":\"secret-pw\"}";
            server.createContext("/api/vault/secrets/projects/" + UNREACHABLE + "/credentials/excalibase_app",
                    exchange -> answer(exchange, 200, refused));
            server.createContext("/api/vault/secrets/projects/" + PAUSED + "/credentials/excalibase_app",
                    exchange -> answer(exchange, 423, "project paused"));
            String jwks = jwks();
            server.createContext("/.well-known/jwks.json", exchange -> answer(exchange, 200, jwks));
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException("provisioning stub", failure);
        }
    }

    /** A key set the engine can start with; no token in this test is signed by it. */
    private static String jwks() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var key = new com.nimbusds.jose.jwk.ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256,
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic())
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(key).toString();
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterAll
    static void stop() {
        provisioning.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        String url = "http://127.0.0.1:" + provisioning.getAddress().getPort() + "/api";
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", "");
        properties.put("management.health.db.enabled", "false");
        properties.put("app.cors.provisioning-url", url);
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.jwks-url", "http://127.0.0.1:" + provisioning.getAddress().getPort() + "/.well-known/jwks.json");
        properties.put("app.security.multi-tenant.provisioning-url", url);
        properties.put("app.security.multi-tenant.provisioning-pat", "test-pat-token");
        properties.put("app.tenant-db.sslmode", "disable");
        return properties;
    }

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    @Test
    void graphql_databaseRefusesConnections_is503ProjectUnavailable() throws Exception {
        http.post("/" + UNREACHABLE + "/graphql", graphql("{ anything { id } }"), "Content-Type", "application/json")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_unavailable")))
                .expect(jsonPath("$.errors[0].message", is("Project unavailable")))
                .expect(content().string(not(containsString("127.0.0.1"))))
                .expect(content().string(not(containsString("secret-pw"))));
    }

    @Test
    void rest_databaseRefusesConnections_isTheSame503() throws Exception {
        http.get("/" + UNREACHABLE + "/api/v1/things")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_unavailable")))
                .expect(content().string(not(containsString("127.0.0.1"))));
    }

    @Test
    void graphql_provisioningSaysPaused_is503ProjectPaused() throws Exception {
        http.post("/" + PAUSED + "/graphql", graphql("{ anything { id } }"), "Content-Type", "application/json")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_paused")))
                .expect(jsonPath("$.errors[0].message", is("Project paused")));
    }

    @Test
    void rest_provisioningSaysPaused_isTheSame503() throws Exception {
        http.get("/" + PAUSED + "/api/v1/things")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_paused")))
                .expect(jsonPath("$.errors[0].message", is("Project paused")));
    }
}
