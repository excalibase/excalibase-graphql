package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An engine with a one-origin allowlist, to see the handshake's origin check refuse on a real socket. */
class WebSocketOriginIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String PATH = "/" + PROJECT + "/graphql";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.unreachableDatabase(PROJECT);
        properties.put("app.cors.allowed-origins", "https://app.example.com");
        return properties;
    }

    private String upgradeAnswer(String origin, String... upgradeValues) throws IOException {
        URI base = http.base();
        StringBuilder request = new StringBuilder("GET " + PATH + " HTTP/1.1\r\nHost: " + base.getHost() + "\r\n");
        for (String value : upgradeValues) {
            request.append("Upgrade: ").append(value).append("\r\n");
        }
        request.append("Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nOrigin: ").append(origin).append("\r\n\r\n");
        try (java.net.Socket socket = new java.net.Socket(base.getHost(), base.getPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8)).readLine();
        }
    }

    @Test
    @DisplayName("an origin off the allowlist is refused at the upgrade")
    void badOriginIsRefused() throws IOException {
        assertThat(upgradeAnswer("https://evil.example", "websocket")).startsWith("HTTP/1.1 403");
    }

    @Test
    @DisplayName("a second Upgrade header after websocket does not skip the origin check")
    void doubledUpgradeStillChecksOrigin() throws IOException {
        assertThat(upgradeAnswer("https://evil.example", "websocket", "foo")).startsWith("HTTP/1.1 403");
    }

    @Test
    @DisplayName("websocket named only after another Upgrade value is never upgraded, whatever the origin")
    void laterWebSocketIsNotUpgraded() throws IOException {
        assertThat(upgradeAnswer("https://evil.example", "foo", "websocket")).startsWith("HTTP/1.1 405");
        assertThat(upgradeAnswer("https://app.example.com", "foo", "websocket")).startsWith("HTTP/1.1 405");
    }
}
