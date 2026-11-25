package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.DatabaseAccess;
import io.github.excalibase.support.InternalSchemaProbe;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * The {@code db} source inside a real engine: with no row every read is 503 and the engine is not ready, a saved
 * document is enforced on a real GraphQL query, an edit is picked up live, a broken edit changes nothing, and the
 * metadata table is absent from introspection and REST.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DbMetadataIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-db";
    private static final String ROLE = "user";
    private static final String TABLE = "excalibase_meta.metadata_documents";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ECPrivateKey privateKey;
    private static HttpServer jwks;

    @Inject
    private MetadataHealth health;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair keyPair = gen.generateKeyPair();
            privateKey = (ECPrivateKey) keyPair.getPrivate();
            String keys = new JWKSet(new ECKey.Builder(Curve.P_256, (ECPublicKey) keyPair.getPublic())
                    .keyUse(KeyUse.SIGNATURE).keyID("test-key").build()).toString();
            jwks = HttpServer.create(new InetSocketAddress(0), 0);
            jwks.createContext("/.well-known/jwks.json", exchange -> {
                byte[] payload = keys.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            jwks.start();
        } catch (Exception e) {
            throw new IllegalStateException("cannot set up the JWKS stub", e);
        }
    }

    @AfterAll
    static void stop() {
        jwks.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", "30");
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.project-id", PROJECT);
        properties.put("app.security.auth.jwks-url", "http://localhost:" + jwks.getAddress().getPort()
                + "/.well-known/jwks.json");
        properties.put("metadata.source", "db");
        properties.put("metadata.db.migrate", "true");
        properties.put("metadata.db.poll-interval-ms", "50");
        return properties;
    }

    private static String rule(String table, String role) {
        return "  - table: " + table + "\n    role: " + role + "\n    select:\n      filter: {}\n      columns: \"*\"\n";
    }

    private static String document(String... rules) {
        return "format: 1\nprojectId: " + PROJECT + "\ntables:\n" + String.join("", rules);
    }

    private static void save(String document) {
        DatabaseAccess.template(postgres).update("INSERT INTO " + TABLE + " (version, document) "
                + "SELECT coalesce(max(version), 0) + 1, ? FROM " + TABLE, document);
    }

    private String jwt() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(String.valueOf("u-1"))
                .claim("projectId", PROJECT).claim("role", ROLE).claim("scope", "authenticated")
                .audience("excalibase:" + PROJECT).issuer("excalibase")
                .issueTime(Date.from(Instant.parse("2024-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2099-01-01T00:00:00Z"))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims);
        signed.sign(new ECDSASigner(privateKey));
        return signed.serialize();
    }

    private HttpAnswer graphql(String query) throws Exception {
        return http.post("/" + PROJECT + "/graphql", MAPPER.writeValueAsString(Map.of("query", query)),
                "Authorization", "Bearer " + jwt(), "Content-Type", "application/json");
    }

    private boolean secretsAreServed() throws Exception {
        return graphql("{ publicSecrets { id } }").body().contains("\"publicSecrets\"")
                && !graphql("{ publicSecrets { id } }").body().contains("Unknown field");
    }

    @Test
    @Order(1)
    void withNoRowYet_everyReadIs503_andTheEngineIsNotReady() throws Exception {
        assertThat(graphql("{ publicOrders { id } }").status()).isEqualTo(503);
        assertThat(health.ready()).isFalse();
    }

    @Test
    @Order(2)
    void aSavedDocument_isEnforcedOnARealQuery_anEditIsPickedUp_andABrokenEditChangesNothing() throws Exception {
        save(document(rule("public.orders", ROLE)));
        await().atMost(Duration.ofSeconds(10)).until(() -> graphql("{ publicOrders { id } }")
                .body().contains("\"publicOrders\""));

        graphql("{ publicOrders { id } }").expect(jsonPath("$.data.publicOrders", hasSize(2)));
        graphql("{ publicSecrets { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));
        assertThat(health.ready()).isTrue();

        save(document(rule("public.orders", ROLE), rule("public.secrets", ROLE)));
        await().atMost(Duration.ofSeconds(10)).until(this::secretsAreServed);

        save(document(rule("public.orders", ROLE)) + "surprise: true\n");
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(this::secretsAreServed);
    }

    @Test
    @Order(3)
    void theMetadataTable_isAbsentFromIntrospection_andRest() throws Exception {
        assertThat(graphql("{ publicOrders { id } }").status()).isEqualTo(200);

        HttpAnswer introspection = graphql(InternalSchemaProbe.INTROSPECTION);
        assertThat(introspection.status()).isEqualTo(200);
        InternalSchemaProbe.assertNothingInternal(introspection.body());
        assertThat(introspection.body().toLowerCase(Locale.ROOT)).doesNotContain("metadata_documents",
                "metadatadocuments");

        for (String profile : List.of("excalibase_meta", "public")) {
            HttpAnswer rest = http.get("/" + PROJECT + "/api/v1/metadata_documents", "Authorization", "Bearer " + jwt(),
                    "Accept-Profile", profile);
            assertThat(rest.status()).as(profile).isEqualTo(404);
            assertThat(rest.body()).doesNotContain("format: 1");
        }
    }
}
