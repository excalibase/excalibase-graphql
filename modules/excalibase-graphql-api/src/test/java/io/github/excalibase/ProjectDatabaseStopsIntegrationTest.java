package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.github.excalibase.support.Expect.content;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * A project that was serving when its database went away (a pause shuts it down) is answered with the same 503 and
 * code as one that never connected, never a generic 500, on GraphQL and on REST. (EXC-812)
 */
class ProjectDatabaseStopsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj_stops12345";
    private static final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-tenant-a.sql");
    private static HttpServer provisioning;
    private static final ObjectMapper mapper = new ObjectMapper();

    @AfterAll
    static void stop() {
        if (provisioning != null) {
            provisioning.stop(0);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        database.start();
        try {
            provisioning = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String credentials = "{\"host\":\"" + database.getHost() + "\",\"port\":\"" + database.getFirstMappedPort()
                    + "\",\"database\":\"" + database.getDatabaseName() + "\",\"username\":\"" + database.getUsername()
                    + "\",\"password\":\"" + database.getPassword() + "\"}";
            provisioning.createContext("/api/vault/secrets/projects/" + PROJECT + "/credentials/excalibase_app",
                    exchange -> answer(exchange, credentials));
            String document = PermissionDocs.document(PROJECT,
                    PermissionDocs.entry("tenant.products", "anon", PermissionDocs.everything()));
            PermissionDocs.serve(provisioning, PROJECT, () -> document);
            String jwks = jwks();
            provisioning.createContext("/.well-known/jwks.json", exchange -> answer(exchange, jwks));
            provisioning.start();
        } catch (Exception failure) {
            throw new IllegalStateException("provisioning stub", failure);
        }
        String url = "http://127.0.0.1:" + provisioning.getAddress().getPort() + "/api";
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", "");
        properties.put("management.health.db.enabled", "false");
        properties.put("app.cors.provisioning-url", url);
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.auth.jwks-url",
                "http://127.0.0.1:" + provisioning.getAddress().getPort() + "/.well-known/jwks.json");
        properties.put("app.security.multi-tenant.provisioning-url", url);
        properties.put("app.security.multi-tenant.provisioning-pat", "test-pat-token");
        properties.put("app.security.rls.policy-url", url);
        properties.put("app.security.rls.policy-pat", "test-pat-token");
        properties.put("app.tenant-db.sslmode", "disable");
        return properties;
    }

    private static String jwks() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var key = new com.nimbusds.jose.jwk.ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256,
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic())
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).keyID("test-key").build();
        return new com.nimbusds.jose.jwk.JWKSet(key).toString();
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void aDatabaseThatStopsWhileServing_is503ProjectUnavailable_onGraphqlAndRest() throws Exception {
        String query = mapper.writeValueAsString(Map.of("query", "{ tenantProducts { id } }"));
        http.post("/" + PROJECT + "/graphql", query, "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.tenantProducts", hasSize(3)));

        String port = String.valueOf(database.getFirstMappedPort());
        database.stop();

        http.post("/" + PROJECT + "/graphql", query, "Content-Type", "application/json")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.errors[0].extensions.code", is("project_unavailable")))
                .expect(content().string(not(containsString(port))));
        http.get("/" + PROJECT + "/api/v1/products", "Accept-Profile", "tenant")
                .expect(status().isServiceUnavailable())
                .expect(jsonPath("$.code", is("project_unavailable")))
                .expect(jsonPath("$.message", is("Project unavailable")));
    }
}
