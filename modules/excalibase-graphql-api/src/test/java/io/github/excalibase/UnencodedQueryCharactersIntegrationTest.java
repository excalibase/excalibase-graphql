package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgREST filters carry characters a browser or curl may send unencoded: braces for arrays
 * ({@code cs.{a,b}}), quotes in lists ({@code in.("a,b")}), brackets. The server takes them, so the
 * request reaches the API and its JSON answer, never the server's own HTML 400 page.
 * Sent over a raw socket because every HTTP client would encode them first.
 */
class UnencodedQueryCharactersIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-insert-only.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Test
    void unencodedBracesQuotesAndBrackets_reachTheApi() {
        HttpAnswer response = http.getUnencoded(
                "/test-proj/api/v1/messages?select=email&email=eq.seed@test.com&source=neq.\"x\"&body=neq.{x}[1]^|");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("content-type")).containsIgnoringCase("application/json");
        assertThat(response.body()).contains("seed@test.com");
    }
}
