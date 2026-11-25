package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.rls.ProjectSockets;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The close codes a client sees on the wire, from a real server: 4408 for a missing init, 1012 for a permission change. */
class WebSocketCloseCodesIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String PATH = "/" + PROJECT + "/graphql";

    @Inject
    ProjectSockets sockets;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(EngineProperties.unreachableDatabase(PROJECT));
        properties.put("app.websocket.init-timeout-seconds", "1");
        return properties;
    }

    @Test
    @DisplayName("a socket that sends no connection_init is closed with 4408")
    void noInitIsClosedWith4408() throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");

            RawWebSocket.Frame answer = socket.readFrame();

            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(4408);
        }
    }

    @Test
    @DisplayName("after its close frame the server keeps the connection until the client answers with its own")
    void connectionStaysOpenUntilTheClientAnswersTheClose() throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH, null)) {
            assertThat(socket.readFrame().opcode()).isEqualTo(RawWebSocket.CLOSE);

            assertThat(socket.serverClosedWithin(500)).as("closed before the client answered").isFalse();

            socket.sendClose(4408);

            assertThat(socket.serverClosedWithin(5000)).as("closed after the client answered").isTrue();
        }
    }

    @Test
    @DisplayName("a permission change closes an open socket with 1012")
    void permissionChangeClosesWith1012() throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH,
                "{\"type\":\"connection_init\",\"payload\":{}}")) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");
            assertThat(socket.readFrame().text()).isEqualTo("{\"type\":\"connection_ack\"}");

            sockets.closeForPermissionChange(PROJECT);

            RawWebSocket.Frame answer = socket.readFrame();
            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(1012);
        }
    }
}
