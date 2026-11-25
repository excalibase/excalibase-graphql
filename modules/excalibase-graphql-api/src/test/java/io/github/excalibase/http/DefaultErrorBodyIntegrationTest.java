package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a client gets when the framework, not a handler, refuses the request: the default error body. */
class DefaultErrorBodyIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String TIMESTAMP = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    private static void assertDefaultBody(HttpAnswer answer, int status, String reason, String path) {
        assertThat(answer.status()).isEqualTo(status);
        assertThat(answer.header("Content-Type")).isEqualTo("application/json");
        JsonNode body = answer.json();
        assertThat(body.fieldNames()).toIterable().containsExactly("timestamp", "status", "error", "path");
        assertThat(body.get("timestamp").asText()).matches(TIMESTAMP);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("error").asText()).isEqualTo(reason);
        assertThat(body.get("path").asText()).isEqualTo(path);
    }

    @Test
    @DisplayName("a path no route serves, under a project, is a 404 with the default body, query left out of the path")
    void noRoute() {
        assertDefaultBody(http.get("/" + PROJECT + "/nothing?x=1"), 404, "Not Found", "/" + PROJECT + "/nothing");
    }

    @Test
    @DisplayName("a trailing slash is not the route")
    void trailingSlash() {
        assertDefaultBody(http.postJson("/" + PROJECT + "/graphql/", Map.of("query", "{ __typename }")), 404,
                "Not Found", "/" + PROJECT + "/graphql/");
    }

    @Test
    @DisplayName("malformed JSON is a 400 with the default body")
    void malformedJson() {
        assertDefaultBody(http.post("/" + PROJECT + "/graphql", "{nope", "Content-Type", "application/json"), 400,
                "Bad Request", "/" + PROJECT + "/graphql");
    }

    @Test
    @DisplayName("a body type the endpoint does not read is a 415")
    void unsupportedMediaType() {
        assertDefaultBody(http.post("/" + PROJECT + "/graphql", "{}", "Content-Type", "text/plain"), 415,
                "Unsupported Media Type", "/" + PROJECT + "/graphql");
    }

    @Test
    @DisplayName("a GET on the GraphQL endpoint is a 405 naming POST")
    void methodNotAllowed() {
        HttpAnswer answer = http.get("/" + PROJECT + "/graphql?query=%7B__typename%7D");

        assertDefaultBody(answer, 405, "Method Not Allowed", "/" + PROJECT + "/graphql");
        assertThat(answer.header("Allow")).isEqualTo("POST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1abc", "a-b", "a.b.c", "bad%20name"})
    @DisplayName("an rpc name that is not an identifier is an unhandled failure: a 500 with the default body")
    void rpcNameIsNotAnIdentifier(String name) {
        String path = "/" + PROJECT + "/api/v1/rpc/" + name;

        assertDefaultBody(http.postJson(path, "{}"), 500, "Internal Server Error", path);
        assertDefaultBody(http.get(path), 500, "Internal Server Error", path);
    }

    @Test
    @DisplayName("an rpc name of 64 characters is one too long to be an identifier")
    void rpcNameTooLong() {
        String path = "/" + PROJECT + "/api/v1/rpc/" + "x".repeat(64);

        assertDefaultBody(http.postJson(path, "{}"), 500, "Internal Server Error", path);
        assertDefaultBody(http.get(path), 500, "Internal Server Error", path);
    }

    @Test
    @DisplayName("a 6 MiB JSON body is read, not refused for its size; over 8 MiB it is a 413 (RequestBodyLimitIntegrationTest)")
    void largeJsonBody() {
        String padded = "{\"query\":\"\",\"pad\":\"" + "x".repeat(6 * 1024 * 1024) + "\"}";

        HttpAnswer answer = http.postJson("/" + PROJECT + "/graphql", padded);

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.json().at("/errors/0/message").asText()).isEqualTo("Missing or invalid 'query' field");
    }
}
