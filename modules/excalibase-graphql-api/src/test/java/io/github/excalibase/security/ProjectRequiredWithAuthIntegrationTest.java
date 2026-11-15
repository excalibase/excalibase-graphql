package io.github.excalibase.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** With authentication on, a path without a project is the same 404 as without it, with or without a token. */
class ProjectRequiredWithAuthIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("app.security.jwt-enabled", "true");
        properties.put("app.security.insecure-dev-mode", "false");
        properties.put("app.security.auth.hmac-secret", "0123456789abcdef0123456789abcdef0123456789");
        return properties;
    }

    @Test
    @DisplayName("a POST to /graphql, with or without a bearer token, is a 404 project_not_found")
    void unscopedGraphqlIsNotFound() {
        for (String[] headers : new String[][] {{"Content-Type", "application/json"},
                {"Content-Type", "application/json", "Authorization", "Bearer abc.def.ghi"}}) {
            HttpAnswer answer = http.post("/graphql", "{\"query\":\"{ __typename }\"}", headers);

            assertThat(answer.status()).isEqualTo(404);
            assertThat(answer.body()).contains("project_not_found");
        }
    }
}
