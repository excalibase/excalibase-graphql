package io.github.excalibase.cors;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The exact CORS answers a client sees: the servlet stack's formats, headers and bodies. */
class CorsAnswerIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String ALLOWED = "https://app.example.test";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("app.cors.allowed-origins", ALLOWED);
        return properties;
    }

    private HttpAnswer preflight(String origin) {
        return http.raw("OPTIONS", "/" + PROJECT + "/graphql", "Origin", origin,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type,x-excalibase-role");
    }

    @Test
    @DisplayName("an allowed preflight lists the methods without spaces, echoes the requested headers, and has no body or type")
    void allowedPreflight() {
        HttpAnswer answer = preflight(ALLOWED);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(answer.header("Access-Control-Allow-Methods")).isEqualTo("GET,POST,PUT,PATCH,DELETE,OPTIONS");
        assertThat(answer.header("Access-Control-Allow-Headers")).isEqualTo("authorization, content-type, x-excalibase-role");
        assertThat(answer.header("Access-Control-Max-Age")).isEqualTo("3600");
        assertThat(answer.header("Access-Control-Expose-Headers")).isEqualTo("Content-Range, Location");
        assertThat(answer.header("Content-Type")).isNull();
        assertThat(answer.body()).isEmpty();
        assertThat(answer.headers().allValues("Vary")).containsExactly("Origin", "Access-Control-Request-Method",
                "Access-Control-Request-Headers");
    }

    @Test
    @DisplayName("a refused preflight is a 403 with a plain text body and no type")
    void refusedPreflight() {
        HttpAnswer answer = preflight("https://evil.example.test");

        assertThat(answer.status()).isEqualTo(403);
        assertThat(answer.body()).isEqualTo("Invalid CORS request");
        assertThat(answer.header("Content-Type")).isNull();
        assertThat(answer.header("Access-Control-Allow-Origin")).isNull();
        assertThat(answer.header("X-Frame-Options")).isEqualTo("DENY");
    }
}
