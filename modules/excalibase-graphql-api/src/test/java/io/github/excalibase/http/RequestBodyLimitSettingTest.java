package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EXC-726: the limit is {@code app.max-request-body} (APP_MAX_REQUEST_BODY), as it was in the servlet build. */
class RequestBodyLimitSettingTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("app.max-request-body", "1MB");
        return properties;
    }

    private static String body(int padBytes) {
        return "{\"query\":\"{ __typename }\",\"pad\":\"" + "x".repeat(padBytes) + "\"}";
    }

    @Test
    @DisplayName("a lower app.max-request-body refuses a body that the default would read")
    void settingLowersTheLimit() {
        assertThat(http.post("/" + PROJECT + "/graphql", body(2 * 1024 * 1024), "Content-Type", "application/json")
                .status()).isEqualTo(413);
        assertThat(http.post("/" + PROJECT + "/graphql", body(100 * 1024), "Content-Type", "application/json")
                .status()).isNotEqualTo(413);
    }
}
