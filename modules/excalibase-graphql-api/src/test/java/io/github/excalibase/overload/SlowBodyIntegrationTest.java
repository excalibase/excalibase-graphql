package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.RawWebSocket;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A request body that stops arriving gives its connection back after the read timeout, as Tomcat's connection timeout did. */
class SlowBodyIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final long READ_TIMEOUT_MILLIS = 1000;

    @Override
    public Map<String, String> getProperties() {
        return Map.ofEntries(
                Map.entry("spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent"),
                Map.entry("spring.datasource.username", "fake"),
                Map.entry("spring.datasource.password", "fake"),
                Map.entry("app.project-id", PROJECT),
                Map.entry("app.security.jwt-enabled", "false"),
                Map.entry("management.health.db.enabled", "false"),
                Map.entry("app.security.insecure-dev-mode", "true"),
                Map.entry("app.database-type", "postgres"),
                Map.entry("server.tomcat.connection-timeout", Long.toString(READ_TIMEOUT_MILLIS)),
                Map.entry("endpoints.all.port", "-1"));
    }

    private static final String HEAD = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
            + "Content-Type: application/json\r\nContent-Length: 200\r\n\r\n";

    @Test
    @DisplayName("a body that stops after the headers is cut off after the read timeout")
    void stalledBodyIsClosed() throws Exception {
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            out.write((HEAD + "{\"query\":").getBytes(StandardCharsets.UTF_8));
            out.flush();
            long started = System.nanoTime();
            int read = socket.getInputStream().read();
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            assertThat(read).as("the server closes the connection").isEqualTo(-1);
            assertThat(seconds).isLessThan(4);
        }
    }

    @Test
    @DisplayName("a body that keeps arriving, slowly, is not cut off")
    void trickleWithinTheTimeoutSurvives() throws Exception {
        byte[] body = "{\"query\":\"{ __typename }\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 5; i++) {
                out.write(body[i]);
                out.flush();
                Thread.sleep(READ_TIMEOUT_MILLIS / 2);
            }
            out.write(body, 5, body.length - 5);
            out.flush();
            byte[] answer = new byte[8];
            assertThat(socket.getInputStream().readNBytes(answer, 0, 8)).isEqualTo(8);
            assertThat(new String(answer, StandardCharsets.UTF_8)).isEqualTo("HTTP/1.1");
        }
    }

    @Test
    @DisplayName("an idle websocket is not closed by the read timeout")
    void idleWebSocketSurvives() throws Exception {
        try (RawWebSocket socket = RawWebSocket.connect(URI.create("ws://localhost:" + server.getPort()),
                "/" + PROJECT + "/graphql", null)) {
            Thread.sleep(READ_TIMEOUT_MILLIS * 3);
            socket.sendText("{\"type\":\"connection_init\"}");
            assertThat(socket.readFrame().text()).contains("connection_ack");
        }
    }
}
