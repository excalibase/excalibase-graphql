package io.github.excalibase.overload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** A real server flooded past its in-flight cap answers 503 to the excess and keeps serving afterwards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {io.github.excalibase.ExcalibaseGraphqlApplication.class,
                OverloadSheddingIntegrationTest.SlowEndpoint.class})
class OverloadSheddingIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");
        registry.add("app.project-id", () -> PROJECT);
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.overload.max-in-flight-per-cpu", () -> "1");
        registry.add("app.overload.queue-wait-ms", () -> "50");
        registry.add("app.overload.retry-after-seconds", () -> "2");
    }

    @TestConfiguration
    @RestController
    static class SlowEndpoint {
        @GetMapping("/" + PROJECT + "/slow")
        String slow() throws InterruptedException {
            Thread.sleep(1500);
            return "ok";
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private CompletableFuture<HttpResponse<String>> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a flood past the cap gets 503 with Retry-After, actuator is never shed, and the server recovers")
    void flood_isShed_serverSurvives() throws Exception {
        int flood = CPUS * 8;
        List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
        for (int i = 0; i < flood; i++) {
            responses.add(get("/" + PROJECT + "/slow"));
        }
        Thread.sleep(300);
        HttpResponse<String> health = get("/actuator/health").get();
        assertThat(health.body()).doesNotContain("overloaded");
        assertThat(get("/actuator/info").get().statusCode()).isEqualTo(200);

        long served = 0;
        long shed = 0;
        for (CompletableFuture<HttpResponse<String>> future : responses) {
            HttpResponse<String> response = future.get();
            if (response.statusCode() == 503) {
                shed++;
                assertThat(response.headers().firstValue("Retry-After")).contains("2");
                assertThat(response.body()).contains("\"code\":\"overloaded\"");
            } else if (response.statusCode() == 200) {
                served++;
            }
        }
        assertThat(shed).isGreaterThan(0);
        assertThat(served).isBetween(1L, (long) CPUS);
        assertThat(served + shed).isEqualTo(flood);

        assertThat(get("/" + PROJECT + "/slow").get().statusCode()).isEqualTo(200);
    }
}
