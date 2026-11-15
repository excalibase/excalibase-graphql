package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The close code a client over its socket cap sees on the wire, from a real server: 1013. */
class WebSocketLimitIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String PATH = "/" + PROJECT + "/graphql";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("app.overload.enabled", "false");
        properties.put("app.overload.max-connections-per-cpu", "1");
        properties.put("app.websocket.max-per-client-percent", "1");
        properties.put("app.websocket.max-per-project-percent", "100");
        return properties;
    }

    @Test
    @DisplayName("a second socket from a client at its cap is closed with 1013")
    void socketOverTheClientCapIsClosedWith1013() throws IOException {
        try (RawWebSocket first = RawWebSocket.connect(http.base(), PATH, null);
             RawWebSocket second = RawWebSocket.connect(http.base(), PATH, null)) {
            assertThat(first.handshakeAnswer()).startsWith("HTTP/1.1 101");
            assertThat(second.handshakeAnswer()).startsWith("HTTP/1.1 101");

            RawWebSocket.Frame answer = second.readFrame();

            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(1013);
        }
    }
}
