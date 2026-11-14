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
 * Not a port regression: Tomcat answers {@code Expect: 100-continue} itself, and the upload after it is
 * not an idle keep-alive connection (the Micronaut engine needed a fix for this).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpectContinueIntegrationTest {

    private static final String PROJECT = "test-proj";

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
        registry.add("app.overload.keep-alive-timeout-ms", () -> "300");
        registry.add("server.tomcat.connection-timeout", () -> "10000");
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("an upload that follows 100 Continue and outlasts the keep-alive timeout is answered")
    void slowUploadAfterContinue() throws Exception {
        byte[] body = "{\"query\":\"{ __typename }\"}".getBytes(StandardCharsets.UTF_8);
        String head = "POST /" + PROJECT + "/graphql HTTP/1.1\r\nHost: localhost\r\nExpect: 100-continue\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder interim = new StringBuilder();
            while (!interim.toString().endsWith("\r\n\r\n")) {
                interim.append((char) in.read());
            }
            assertThat(interim.toString()).startsWith("HTTP/1.1 100");
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
