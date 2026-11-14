package io.github.excalibase.overload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not a port regression: Tomcat's connection timeout already cuts off a request body that stops arriving
 * (the Micronaut engine needed its own timer for this).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlowBodyIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final long READ_TIMEOUT_MILLIS = 1000;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("management.health.db.enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("server.tomcat.connection-timeout", () -> Long.toString(READ_TIMEOUT_MILLIS));
    }

    @LocalServerPort
    private int port;

    private static final String HEAD = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
            + "Content-Type: application/json\r\nContent-Length: 200\r\n\r\n";

    @Test
    @DisplayName("a body that stops after the headers is cut off after the read timeout")
    void stalledBodyIsClosed() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(6000);
            OutputStream out = socket.getOutputStream();
            out.write((HEAD + "{\"query\":").getBytes(StandardCharsets.UTF_8));
            out.flush();
            long started = System.nanoTime();
            InputStream in = socket.getInputStream();
            int first = in.read();
            long seconds = (System.nanoTime() - started) / 1_000_000_000L;
            // Tomcat answers 400 (or just closes): either way the connection is not held open.
            if (first != -1) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).startsWith("TTP/1.1 4");
            }
            assertThat(seconds).isLessThan(4);
        }
    }

    @Test
    @DisplayName("a body that keeps arriving, slowly, is not cut off")
    void trickleWithinTheTimeoutSurvives() throws Exception {
        byte[] body = "{\"query\":\"{ __typename }\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (Socket socket = new Socket("localhost", port)) {
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
}
