package io.github.excalibase.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXC-726: a body is read only up to 8 MiB, whatever its type and however its length is announced.
 * A JSON body had no limit before, and a form body was capped at 10 MB by the container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RequestBodyLimitIntegrationTest {

    private static final int MIB = 1024 * 1024;

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

    private final HttpClient client = HttpClient.newHttpClient();

    private static String json(int padBytes) {
        return "{\"query\":\"{ __typename }\",\"pad\":\"" + "x".repeat(padBytes) + "\"}";
    }

    private HttpResponse<String> post(String path, String contentType, BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType).POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A publisher that announces no length, so the body goes out chunked. */
    private static BodyPublisher chunked(String body) {
        BodyPublisher whole = BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        return BodyPublishers.fromPublisher(new Flow.Publisher<ByteBuffer>() {
            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                whole.subscribe(subscriber);
            }
        });
    }

    @Test
    @DisplayName("a GraphQL JSON body of 9 MiB is refused with 413")
    void oversizedGraphqlBodyIsRefused() throws Exception {
        assertThat(post("/test-proj/graphql", "application/json", BodyPublishers.ofString(json(9 * MIB))).statusCode())
                .isEqualTo(413);
    }

    @Test
    @DisplayName("the 413 carries the security headers and Vary of any other error, in the default error body")
    void refusalLooksLikeOtherSpringErrors() throws Exception {
        HttpResponse<String> response = post("/test-proj/graphql", "application/json",
                BodyPublishers.ofString(json(9 * MIB)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("vary").orElse("")).contains("Origin");
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"status\":413").contains("Payload Too Large");
    }

    @Test
    @DisplayName("a REST JSON body of 9 MiB is refused with 413")
    void oversizedRestBodyIsRefused() throws Exception {
        assertThat(post("/test-proj/api/v1/messages", "application/json", BodyPublishers.ofString(json(9 * MIB))).statusCode())
                .isEqualTo(413);
    }

    @Test
    @DisplayName("a chunked body of 9 MiB, with no announced length, is refused with 413")
    void oversizedChunkedBodyIsRefused() throws Exception {
        assertThat(post("/test-proj/graphql", "application/json", chunked(json(9 * MIB))).statusCode())
                .isEqualTo(413);
    }

    @Test
    @DisplayName("a form body of 9 MiB is refused with 413 on GraphQL and REST")
    void oversizedFormBodyIsRefused() throws Exception {
        String form = "a=" + "x".repeat(9 * MIB);
        for (String path : List.of("/test-proj/graphql", "/test-proj/api/v1/messages")) {
            assertThat(post(path, "application/x-www-form-urlencoded", BodyPublishers.ofString(form)).statusCode())
                    .as(path).isEqualTo(413);
        }
    }

    @Test
    @DisplayName("a body of 6 MiB, more than a 1000-row bulk insert, is still read")
    void largeLegitimateBodyIsServed() throws Exception {
        assertThat(post("/test-proj/graphql", "application/json", BodyPublishers.ofString(json(6 * MIB))).statusCode())
                .isNotEqualTo(413);
        assertThat(post("/test-proj/graphql", "application/json", chunked(json(6 * MIB))).statusCode())
                .isNotEqualTo(413);
    }
}
