package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import java.net.URI;
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
        Map<String, String> properties = EngineProperties.unreachableDatabase(PROJECT);
        return properties;
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

    @Test
    @DisplayName("a frame over the size limit closes the session with 1009 and is logged at WARN")
    void oversizedFrameIsLoggedAtWarn() throws IOException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), PATH, CONNECTION_INIT)) {
            assertThat(socket.readFrame().text()).isEqualTo(CONNECTION_ACK);

            socket.sendText("x".repeat(9000));

            RawWebSocket.Frame answer = socket.readFrame();
            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(1009);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(captured.list)
                    .filteredOn(event -> event.getFormattedMessage().contains("oversized"))
                    .extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN));
        } finally {
            root.detachAppender(captured);
        }
    }
}
