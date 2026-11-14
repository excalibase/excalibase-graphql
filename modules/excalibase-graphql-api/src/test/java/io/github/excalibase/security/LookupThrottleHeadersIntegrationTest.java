package io.github.excalibase.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** EXC-722: the 429 of a throttled project lookup is readable by a browser and carries the security headers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LookupThrottleHeadersIntegrationTest {

    @TestConfiguration
    static class Throttled {
        @Bean
        @Primary
        KnownProjects alwaysThrottled() {
            return projectId -> {
                throw new ProjectLookupThrottledException();
            };
        }
    }

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
    @DisplayName("a throttled lookup answers 429 with CORS for the asking origin, Vary and the security headers")
    void throttledAnswerIsReadableByABrowser() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/some-project/graphql"))
                        .header("Origin", "https://app.example.com")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ __typename }\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("access-control-allow-origin")).isPresent();
        assertThat(response.headers().firstValue("vary").orElse("")).contains("Origin");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @Test
    @DisplayName("a throttled preflight is answered 204 with the allowed methods and no caching")
    void throttledPreflightIsAnswered() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/some-project/graphql"))
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("access-control-allow-origin")).contains("*");
        assertThat(response.headers().firstValue("access-control-allow-methods").orElse("")).contains("POST");
        assertThat(response.headers().firstValue("access-control-allow-headers")).contains("authorization");
        assertThat(response.headers().firstValue("access-control-max-age")).contains("0");
    }
}
