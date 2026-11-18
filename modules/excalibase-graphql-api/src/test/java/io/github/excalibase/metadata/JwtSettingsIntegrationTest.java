package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * A standalone engine started with no JWT settings of its own accepts an RS256 token from an outside identity
 * provider, takes the caller's id from {@code sub}, and a rule on that id decides which rows come back.
 */
class JwtSettingsIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-jwt";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JwksStub IDP = new JwksStub();
    private static final Path FILE = newFile();

    private static Path newFile() {
        try {
            Path file = Files.createTempDirectory("metadata-jwt-it").resolve("permissions.yaml");
            Files.writeString(file, """
                    format: 1
                    projectId: %s
                    jwt:
                      issuer: "%s"
                      jwksUrl: %s
                      audience: %s
                      claims:
                        role: https://demo/role
                    tables:
                      - table: public.orders
                        role: user
                        select:
                          filter: {"customer": {"_eq": "X-Excalibase-User-Id"}}
                          columns: "*"
                    """.formatted(PROJECT, IDP.issuer(), IDP.jwksUrl(), JwksStub.AUDIENCE));
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
        properties.put("metadata.source", "file");
        properties.put("metadata.file.path", FILE.toString());
        properties.put("metadata.file.poll-interval-ms", "50");
        return properties;
    }

    private HttpAnswer orders(String token) throws Exception {
        return http.post("/" + PROJECT + "/graphql",
                MAPPER.writeValueAsString(Map.of("query", "{ publicOrders { id customer } }")),
                "Authorization", "Bearer " + token, "Content-Type", "application/json");
    }

    private String token(String subject, String audience) {
        return IDP.rs256(IDP.claims().subject(subject).audience(audience)
                .claim("https://demo/role", "user").build());
    }

    @Test
    void anRs256TokenFromTheOutsideProvider_runsAsTheCallerInTheSubClaim() throws Exception {
        orders(token("alice", JwksStub.AUDIENCE)).expect(jsonPath("$.data.publicOrders", hasSize(1)))
                .expect(jsonPath("$.data.publicOrders[0].customer", is("alice")));
        orders(token("bob", JwksStub.AUDIENCE)).expect(jsonPath("$.data.publicOrders[0].id",
                is(2)));
    }

    @Test
    void aTokenForAnotherAudience_isRefused() throws Exception {
        assertThat(orders(token("alice", "https://someone-else")).status()).isEqualTo(401);
    }
}
