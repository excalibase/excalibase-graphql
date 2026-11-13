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

    private String firstLine(String path, String... upgradeValues) throws java.io.IOException {
        java.net.URI base = http.base();
        StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + base.getHost() + "\r\n");
        for (String value : upgradeValues) {
            request.append("Upgrade: ").append(value).append("\r\n");
        }
        request.append("Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n");
        try (java.net.Socket socket = new java.net.Socket(base.getHost(), base.getPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8)).readLine();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"foo|websocket", "foo, websocket", "h2c|WebSocket"})
    @DisplayName("an upgrade the servlet stack did not see (websocket not the first Upgrade value) is answered as a plain GET")
    void disguisedUpgradeIsAPlainRequest(String lines) throws java.io.IOException {
        assertThat(firstLine("/" + PROJECT + "/graphql", lines.split("\\|"))).startsWith("HTTP/1.1 405");
        assertThat(firstLine("/" + PROJECT + "/api/v1/realtime", lines.split("\\|"))).startsWith("HTTP/1.1 404");
    }
}
