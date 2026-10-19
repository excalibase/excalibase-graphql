package io.github.excalibase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgREST filters carry characters a browser or curl may send unencoded: braces for arrays
 * ({@code cs.{a,b}}), quotes in lists ({@code in.("a,b")}), brackets. The server takes them, so the
 * request reaches the API and its JSON answer, never the servlet container's own HTML 400 page.
 * Sent over a raw socket because every HTTP client would encode them first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UnencodedQueryCharactersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-insert-only.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Test
    void unencodedBracesQuotesAndBrackets_reachTheApi() throws Exception {
        String response = rawGet("/test-proj/api/v1/messages?select=email&email=eq.seed@test.com&source=neq.\"x\"&body=neq.{x}[1]^|");

        assertThat(response).startsWith("HTTP/1.1 200");
        assertThat(response).containsIgnoringCase("content-type: application/json");
        assertThat(response).contains("seed@test.com");
    }

    private String rawGet(String target) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
