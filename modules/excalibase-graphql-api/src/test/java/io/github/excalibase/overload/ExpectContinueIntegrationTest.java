package io.github.excalibase.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The interim 100 Continue answer is not the end of the request: the upload after it is not an idle connection. */
class ExpectContinueIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";

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
                Map.entry("app.overload.keep-alive-timeout-ms", "300"),
                Map.entry("server.tomcat.connection-timeout", "10000"),
                Map.entry("endpoints.all.port", "-1"));
    }

    @Test
    @DisplayName("an upload that follows 100 Continue and outlasts the keep-alive timeout is answered")
    void slowUploadAfterContinue() throws Exception {
        byte[] body = "{\"query\":\"{ __typename }\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (Socket socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] interim = new byte[25];
            assertThat(in.readNBytes(interim, 0, interim.length)).isEqualTo(interim.length);
            assertThat(new String(interim, StandardCharsets.UTF_8)).isEqualTo("HTTP/1.1 100 Continue\r\n\r\n");
            for (int i = 0; i < 5; i++) {
                out.write(body[i]);
                out.flush();
                Thread.sleep(400);
            }
            out.write(body, 5, body.length - 5);
            out.flush();
            byte[] answer = new byte[12];
            assertThat(in.readNBytes(answer, 0, answer.length)).isEqualTo(answer.length);
            assertThat(new String(answer, StandardCharsets.UTF_8)).isEqualTo("HTTP/1.1 200");
        }
    }
}
