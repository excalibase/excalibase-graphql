package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/** Content negotiation on the REST root and the PostgREST vendor type, as the servlet stack negotiated it. */
class RestMediaTypesIntegrationTest extends EngineIntegrationTest {

    private static final String BASE = "/test-proj/api/v1";
    private static final String OPENAPI = "application/openapi+json";
    private static final String VENDOR = "application/vnd.pgrst.object+json";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Test
    @DisplayName("the root asked for as plain JSON is a 406 with the default body: it only produces the OpenAPI type")
    void rootAsPlainJson() {
        HttpAnswer answer = http.get(BASE, "Accept", "application/json");

        assertThat(answer.status()).isEqualTo(406);
        assertThat(answer.header("Content-Type")).isEqualTo("application/json");
        assertThat(answer.json().get("error").asText()).isEqualTo("Not Acceptable");
        assertThat(answer.json().get("path").asText()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("the root asked for as OpenAPI, any type, or nothing in particular is the OpenAPI document")
    void rootAsOpenApi() {
        for (String accept : new String[] {OPENAPI, "*/*", "application/*", "text/html, */*;q=0.1"}) {
            HttpAnswer answer = http.get(BASE, "Accept", accept);

            assertThat(answer.status()).as(accept).isEqualTo(200);
            assertThat(answer.header("Content-Type")).as(accept).isEqualTo(OPENAPI);
        }
    }

    @Test
    @DisplayName("the vendor type with more than one row is a 406 that carries the vendor type")
    void vendorTypeManyRows() {
        HttpAnswer answer = http.get(BASE + "/products?limit=2", "Accept", VENDOR);

        assertThat(answer.status()).isEqualTo(406);
        assertThat(answer.header("Content-Type")).isEqualTo(VENDOR);
        assertThat(answer.json().get("code").asText()).isEqualTo("not_acceptable");
    }
}
