package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EXC-726: a body is read into memory only up to 8 MiB; a bulk insert of the engine's 1000-row cap fits well inside. */
class RequestBodyLimitIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String GRAPHQL = "/" + PROJECT + "/graphql";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    private static String body(int padBytes) {
        return "{\"query\":\"{ __typename }\",\"pad\":\"" + "x".repeat(padBytes) + "\"}";
    }

    @Test
    @DisplayName("a body of 9 MiB is refused with 413 before it is buffered")
    void oversizedBodyIsRefused() {
        HttpAnswer answer = http.post(GRAPHQL, body(9 * 1024 * 1024), "Content-Type", "application/json");

        assertThat(answer.status()).isEqualTo(413);
    }

    @Test
    @DisplayName("a body of 6 MiB, more than a 1000-row bulk insert, is still read")
    void largeLegitimateBodyIsServed() {
        HttpAnswer answer = http.post(GRAPHQL, body(6 * 1024 * 1024), "Content-Type", "application/json");

        assertThat(answer.status()).isNotEqualTo(413);
    }
}
