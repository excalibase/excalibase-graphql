package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Request targets the servlet stack refused before routing are refused the same way here. */
class RequestFirewallIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String HTML_400_TITLE = "<title>HTTP Status 400 – Bad Request</title>";

    @Inject
    MeterRegistry registry;

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/api/v1/customer;x=1", "/test-proj/api/v1/../graphql", "//test-proj/graphql",
            "/test-proj/api/v1/./customer", "/test-proj/api/v1/customer%3Bx", "/test-proj/api/v1/a%2ee",
            "/test-proj/api/v1/a%25b", "/test-proj/api/v1/customer/..",
            "/test-proj/api/v1/rpc/x%0ay", "/test-proj/api/v1/rpc/x%0Ay", "/test-proj/api/v1/rpc/x%0dy",
            "/test-proj/api/v1/rpc/x%0Dy", "/test-proj/api/v1/rpc/x%E2%80%A8y", "/test-proj/api/v1/rpc/x%e2%80%a9y"})
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

    @Test
    @DisplayName("a rejected request is counted under the route its path would have matched, named by the rejection")
    void rejectedRequestIsCountedUnderItsRoute() {
        assertThat(http.raw("GET", "/test-proj/api/v1/rpc/no-such;fn").status()).isEqualTo(400);

        var timer = registry.find("http.server.requests").tag("status", "400")
                .tag("uri", "/{projectId}/api/v1/rpc/{function}").tag("exception", "RequestRejectedException").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.getId().getTag("outcome")).isEqualTo("CLIENT_ERROR");
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

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/api/v1/rpc/x\u2028y", "/test-proj/api/v1/rpc/x\u2029y"})
    @DisplayName("a raw line or paragraph separator in the path is a 400 HTML page, as the server's own parser answers")
    void rawSeparatorRejectedWithHtmlPage(String target) {
        HttpAnswer answer = http.raw("GET", target);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
        assertThat(answer.body()).contains(HTML_400_TITLE);
    }

    @Test
    @DisplayName("TRACE is a 400 with the methods served, an empty body and no security headers")
    void traceIsRefused() {
        HttpAnswer answer = http.raw("TRACE", "/" + PROJECT + "/graphql");

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.header("Allow")).isEqualTo("HEAD, DELETE, POST, GET, OPTIONS, PUT");
        assertThat(answer.body()).isEmpty();
        assertThat(answer.header("X-Frame-Options")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO", "PROPFIND", "MKCOL"})
    @DisplayName("a method the firewall does not know is a 400 with an empty body, on any path")
    void unknownMethodIsRefused(String method) {
        for (String path : new String[] {"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/customer", "/nowhere"}) {
            HttpAnswer answer = http.raw(method, path);

            assertThat(answer.status()).as(method + " " + path).isEqualTo(400);
            assertThat(answer.header("Allow")).isNull();
            assertThat(answer.body()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS", "PATCH", "PUT", "DELETE"})
    @DisplayName("the methods the firewall allows are not refused by it")
    void allowedMethodsPass(String method) {
        assertThat(http.raw(method, "/" + PROJECT + "/nothing.here").status()).isNotEqualTo(400);
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
