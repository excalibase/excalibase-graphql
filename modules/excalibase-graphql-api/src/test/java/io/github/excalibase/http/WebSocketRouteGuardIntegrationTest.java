package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A WebSocket upgrade is admitted only on a path whose project the handshake filter parsed, and frames are capped at 8 KiB. */
class WebSocketRouteGuardIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p/graphql/", "/p/api/v1/realtime/"})
    @DisplayName("an upgrade on a route with a trailing slash, where no project parses, is a 404 and not an unchecked socket")
    void trailingSlashUpgradeIsNotFound(String path) throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 404");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/p%2Fq/graphql", "/p%2Fq/api/v1/realtime", "/p%2fq/graphql", "//graphql", "/a/b/graphql"})
    @DisplayName("an upgrade with an encoded slash or an empty or extra segment is refused, never upgraded")
    void malformedUpgradeIsRefused(String path) throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
            assertThat(socket.handshakeAnswer()).matches("(?s)HTTP/1\\.1 4\\d\\d.*");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/" + PROJECT + "/graphql", "/" + PROJECT + "/api/v1/realtime"})
    @DisplayName("a frame over 8 KiB closes the socket with 1009")
    void oversizedFrameClosesWith1009(String path) throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");
            socket.sendText("{\"type\":\"connection_init\",\"payload\":{\"pad\":\"" + "x".repeat(9000) + "\"}}");

            RawWebSocket.Frame answer = socket.readFrame();
            assertThat(answer.opcode()).isEqualTo(RawWebSocket.CLOSE);
            assertThat(answer.closeCode()).isEqualTo(1009);
        }
    }
}
