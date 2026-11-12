package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bytes of a graphql-transport-ws session that a library client hides: pipelined frames, bare close frames. */
class WebSocketHandshakeIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String PATH = "/" + PROJECT + "/graphql";
    private static final String CONNECTION_INIT = "{\"type\":\"connection_init\",\"payload\":{}}";
    private static final String CONNECTION_ACK = "{\"type\":\"connection_ack\"}";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    @Test
    @DisplayName("a close frame with no status code is answered with close code 1000")
    void emptyCloseIsAnsweredNormalClosure() throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");
            socket.sendText(CONNECTION_INIT);
            assertThat(socket.readFrame().text()).isEqualTo(CONNECTION_ACK);

            socket.sendEmptyClose();

            RawWebSocket.Frame answer = socket.readFrame();
            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(1000);
            assertThat(answer.closeReason()).isEmpty();
        }
    }

    @Test
    @DisplayName("a connection_init in the same write as the upgrade request is not dropped")
    void pipelinedFrameIsNotDropped() throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH, CONNECTION_INIT)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");

            assertThat(socket.readFrame().text()).isEqualTo(CONNECTION_ACK);
        }
    }
}
