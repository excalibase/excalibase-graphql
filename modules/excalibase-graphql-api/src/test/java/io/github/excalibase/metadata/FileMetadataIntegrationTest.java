package io.github.excalibase.metadata;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * A rule written in a metadata file is enforced on a real GraphQL query, a later edit is picked up while the
 * engine runs, a broken edit changes nothing, and a rule naming a table that no longer exists is warned about
 * and skipped instead of refusing the document.
 */
class FileMetadataIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-file";
    private static final String ROLE = "user";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FILE = newFile();

    private static ECPrivateKey privateKey;
    private static HttpServer jwks;

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

    private static Path newFile() {
        try {
            Path file = Files.createTempDirectory("metadata-file-it").resolve("permissions.yaml");
            Files.writeString(file, document(rule("public.orders", ROLE)));
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
        properties.put("metadata.source", "file");
        properties.put("metadata.file.path", FILE.toString());
        properties.put("metadata.file.poll-interval-ms", "50");
        return properties;
    }

    private static String rule(String table, String role) {
        return "  - table: " + table + "\n    role: " + role + "\n    select:\n      filter: {}\n      columns: \"*\"\n";
    }

    private static String document(String... rules) {
        return "format: 1\nprojectId: " + PROJECT + "\ntables:\n" + String.join("", rules);
    }

    private String jwt() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("u@test.com").claim("userId", "u-1")
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
    void aRuleFromTheFile_isEnforcedOnARealQuery_andAnEditIsPickedUpLive() throws Exception {
        Files.writeString(FILE, document(rule("public.orders", ROLE)));
        await().atMost(Duration.ofSeconds(10)).until(() -> !secretsAreServed());

        graphql("{ publicOrders { id } }").expect(jsonPath("$.data.publicOrders", hasSize(2)));
        graphql("{ publicSecrets { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("Unknown field")));

        Files.writeString(FILE, document(rule("public.orders", ROLE), rule("public.secrets", ROLE)));
        await().atMost(Duration.ofSeconds(10)).until(this::secretsAreServed);

        Files.writeString(FILE, document(rule("public.orders", ROLE)) + "surprise: true\n");
        Thread.sleep(300);
        assertThat(secretsAreServed()).as("a bad edit keeps the last good version").isTrue();
    }

    @Test
    void aRuleNamingADroppedTable_isWarnedAboutAndSkipped_notARefusal() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("io.github.excalibase.access.RulesResolver");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Files.writeString(FILE, document(rule("public.orders", ROLE), rule("public.long_gone", ROLE)));
            await().atMost(Duration.ofSeconds(10)).until(() -> graphql("{ publicOrders { id } }")
                    .body().contains("\"publicOrders\""));

            graphql("{ publicOrders { id } }").expect(jsonPath("$.data.publicOrders", hasSize(2)));
            assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("permission_invalid")
                    && event.getFormattedMessage().contains("table=public.long_gone")
                    && event.getFormattedMessage().contains("no such table"));
        } finally {
            logger.detachAppender(appender);
            Files.writeString(FILE, document(rule("public.orders", ROLE)));
        }
    }
}
