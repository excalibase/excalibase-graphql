package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;

/**
 * The engine has an HMAC secret of its own and a metadata file. A document that brings a {@code jwt:} section is
 * refused, and the tokens the engine's own settings verify keep working once a valid document is there.
 */
class EnvJwtWithDocumentFileIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-envjwt";
    private static final String SECRET = "an-hmac-secret-of-at-least-32-bytes!!";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JwksStub IDP = new JwksStub();
    private static final Path FILE = newFile();

    private static final String RULES = """
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;

    private static Path newFile() {
        try {
            Path file = Files.createTempDirectory("metadata-envjwt-it").resolve("permissions.yaml");
            Files.writeString(file, "format: 1\nprojectId: " + PROJECT + "\njwt:\n  issuer: \"" + IDP.issuer()
                    + "\"\n  jwksUrl: " + IDP.jwksUrl() + "\n  audience: " + JwksStub.AUDIENCE + "\n" + RULES);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        IDP.close();
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
        properties.put("app.security.auth.hmac-secret", SECRET);
        properties.put("metadata.source", "file");
        properties.put("metadata.file.path", FILE.toString());
        properties.put("metadata.file.poll-interval-ms", "50");
        return properties;
    }

    private static String envToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("alice").claim("role", "user")
                .audience("excalibase:" + PROJECT).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        SignedJWT signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        signed.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return signed.serialize();
    }

    private HttpAnswer orders() throws Exception {
        return http.post("/" + PROJECT + "/graphql",
                MAPPER.writeValueAsString(Map.of("query", "{ publicOrders { id } }")),
                "Authorization", "Bearer " + envToken(), "Content-Type", "application/json");
    }

    @Test
    void aJwtSectionIsRefused_whileTheEnginesOwnSecretIsSet_andItsTokensStillWork() throws Exception {
        assertThat(orders().status()).as("the document is refused, so nothing is served yet").isEqualTo(503);

        Files.writeString(FILE, "format: 1\nprojectId: " + PROJECT + "\n" + RULES);
        await().atMost(Duration.ofSeconds(10)).until(() -> orders().status() == 200);

        orders().expect(jsonPath("$.data.publicOrders", hasSize(2)));
    }
}
