package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Route matching answers after the project is known, as the servlet dispatch came after the security chain. */
class RouteCompatibilityIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    @Test
    @DisplayName("a plain GET on a project's GraphQL path is a 405 naming POST")
    void plainGetIsNotAllowed() {
        HttpAnswer answer = http.raw("GET", "/" + PROJECT + "/graphql");

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.header("Allow")).isEqualTo("POST");
    }

    @Test
    @DisplayName("a plain GET on an unknown project's GraphQL path is a project_not_found 404, not a 405")
    void unknownProjectIsNotFoundBeforeMethod() {
        HttpAnswer answer = http.raw("GET", "/unknown/graphql");

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.body()).contains("project_not_found");
    }

    @Test
    @DisplayName("a trailing slash on a known project's path is the default 404")
    void trailingSlashOnKnownProject() {
        HttpAnswer answer = http.raw("POST", "/" + PROJECT + "/graphql/");

        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.json().get("error").asText()).isEqualTo("Not Found");
    }
}
