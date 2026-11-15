package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.RawWebSocket;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The security filters read the database, the control plane and a key set, so every request reaches them on the
 * blocking executor, whatever its method or path, an upgrade included. TRACE and unknown methods are refused by
 * the firewall before any of them (RequestFirewallIntegrationTest).
 */
class BlockingHopIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.unreachableDatabase(PROJECT);
        properties.put("test.record-threads", "true");
        return properties;
    }

    @BeforeEach
    void forget() {
        ThreadRecorder.THREADS.clear();
    }

    private static void assertOnRequestThread(String key) {
        assertThat(ThreadRecorder.THREADS).containsKey(key);
        assertThat(ThreadRecorder.THREADS.get(key)).startsWith("tomcat-handler-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"})
    @DisplayName("any method on the GraphQL path reaches the filters on a request thread")
    void everyMethodOnGraphql(String method) {
        http.raw(method, "/" + PROJECT + "/graphql");

        assertOnRequestThread(method + " /" + PROJECT + "/graphql");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "OPTIONS"})
    @DisplayName("any method on the realtime path reaches the filters on a request thread")
    void everyMethodOnRealtime(String method) {
        http.raw(method, "/" + PROJECT + "/api/v1/realtime");

        assertOnRequestThread(method + " /" + PROJECT + "/api/v1/realtime");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/graphql", "/test-proj/api/v1/realtime"})
    @DisplayName("a WebSocket upgrade reaches the filters on a request thread")
    void webSocketUpgrade(String path) throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");
        }

        assertOnRequestThread("GET " + path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test-proj/graphql", "/test-proj/api/v1/realtime"})
    @DisplayName("an upgrade goes back to the event loop once the security filters are done, where Micronaut finishes it")
    void webSocketUpgradeEndsOnTheEventLoop(String path) throws IOException {
        try (RawWebSocket socket = RawWebSocket.connect(http.base(), path, null)) {
            assertThat(socket.handshakeAnswer()).startsWith("HTTP/1.1 101");
        }

        assertThat(ThreadRecorder.THREADS.get("late GET " + path)).contains("eventLoopGroup");
    }

    @Test
    @DisplayName("a request that is not an upgrade stays on its request thread to the handler")
    void plainRequestStaysOnTheRequestThread() {
        http.raw("POST", "/" + PROJECT + "/graphql");

        assertThat(ThreadRecorder.THREADS).as("recorded").containsKey("late POST /" + PROJECT + "/graphql");
        assertThat(ThreadRecorder.THREADS.get("late POST /" + PROJECT + "/graphql")).startsWith("tomcat-handler-");
    }
}
