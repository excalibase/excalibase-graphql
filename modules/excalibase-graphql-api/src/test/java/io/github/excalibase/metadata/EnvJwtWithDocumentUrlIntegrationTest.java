package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;

/** Same rule for the url source, with the engine's own key set (jwks-url) instead of a secret. */
class EnvJwtWithDocumentUrlIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "proj-envjwt-url";

    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-exposure.sql");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JwksStub IDP = new JwksStub();
    private static final String RULES = """
            tables:
              - table: public.orders
                role: user
                select:
                  filter: {}
                  columns: "*"
            """;
    private static volatile String served = "format: 1\nprojectId: " + PROJECT + "\njwt:\n  issuer: \""
            + IDP.issuer() + "\"\n  jwksUrl: " + IDP.jwksUrl() + "\n  audience: " + JwksStub.AUDIENCE + "\n" + RULES;
    private static HttpServer documents;

    static {
        try {
            documents = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            documents.createContext("/permissions.yaml", exchange -> {
                byte[] payload = served.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            documents.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        documents.stop(0);
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
        properties.put("app.security.auth.jwks-url", IDP.jwksUrl());
        properties.put("metadata.source", "url");
        properties.put("metadata.url.location", "http://127.0.0.1:" + documents.getAddress().getPort()
                + "/permissions.yaml");
        properties.put("metadata.url.poll-interval-ms", "50");
        return properties;
    }

    private HttpAnswer orders() throws Exception {
        String token = IDP.es256(IDP.claims().audience("excalibase:" + PROJECT).claim("role", "user").build());
        return http.post("/" + PROJECT + "/graphql",
                MAPPER.writeValueAsString(Map.of("query", "{ publicOrders { id } }")),
                "Authorization", "Bearer " + token, "Content-Type", "application/json");
    }

    @Test
    void aJwtSectionIsRefused_whileTheEnginesOwnJwksUrlIsSet_andItsTokensStillWork() throws Exception {
        assertThat(orders().status()).as("the document is refused, so nothing is served yet").isEqualTo(503);

        served = "format: 1\nprojectId: " + PROJECT + "\n" + RULES;
        await().atMost(Duration.ofSeconds(10)).until(() -> orders().status() == 200);

        orders().expect(jsonPath("$.data.publicOrders", hasSize(2)));
    }
}
