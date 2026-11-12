package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request targets the servlet stack refused before routing are refused the same way here. */
class RequestFirewallIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String HTML_400_TITLE = "<title>HTTP Status 400 – Bad Request</title>";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/api/v1/customer;x=1", "/test-proj/api/v1/../graphql", "//test-proj/graphql",
            "/test-proj/api/v1/./customer", "/test-proj/api/v1/customer%3Bx", "/test-proj/api/v1/a%2ee",
            "/test-proj/api/v1/a%25b", "/test-proj/api/v1/customer/.."})
    @DisplayName("a path with a matrix parameter, dot segment, double slash or encoded dot is a 400 with the default body and no security headers")
    void rejectedWithDefaultBody(String target) {
        HttpAnswer answer = http.raw("GET", target);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.header("Content-Type")).isEqualTo("application/json");
        assertThat(answer.json().get("error").asText()).isEqualTo("Bad Request");
        assertThat(answer.json().get("path").asText()).isEqualTo(target);
        assertThat(answer.header("X-Frame-Options")).isNull();
        assertThat(answer.header("Cache-Control")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/api/v1/customer%2f..", "/test-proj/api/v1/customer%2F", "/test-proj/api/v1/a%5cb"})
    @DisplayName("an encoded slash or backslash is a 400 HTML page")
    void rejectedWithHtmlPage(String target) {
        HttpAnswer answer = http.raw("GET", target);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
        assertThat(answer.body()).contains(HTML_400_TITLE);
        assertThat(answer.header("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("request headers beyond 8 KiB are a 400 HTML page, decided before routing")
    void oversizeHeaders() {
        HttpAnswer answer = http.raw("GET", "/" + PROJECT + "/graphql", "X-Big", "y".repeat(70_000));

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
        assertThat(answer.body()).contains(HTML_400_TITLE);
    }

    @Test
    @DisplayName("a request line beyond 8 KiB is a 400 HTML page")
    void oversizeRequestLine() {
        HttpAnswer answer = http.raw("GET", "/" + PROJECT + "/api/v1/customer?x=" + "y".repeat(9_000));

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).contains(HTML_400_TITLE);
    }

    @Test
    @DisplayName("dots inside a name are not dot segments")
    void dotsInsideANameAreFine() {
        assertThat(http.raw("GET", "/" + PROJECT + "/nothing.here..too").status()).isEqualTo(404);
    }
}
