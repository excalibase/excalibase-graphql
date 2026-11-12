package io.github.excalibase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what a real server answers for an rpc name that is not an identifier. The answer is whatever the
 * server does with an unhandled failure (a 500 with Spring's default error body); it is checked over HTTP
 * because MockMvc rethrows the failure instead of answering it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RpcFunctionNameTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:59999/nonexistent");
        registry.add("spring.datasource.username", () -> "fake");
        registry.add("spring.datasource.password", () -> "fake");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
    }

    @Autowired
    private TestRestTemplate http;

    private ResponseEntity<String> call(HttpMethod method, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/test-proj/api/v1/rpc/" + name, method, new HttpEntity<>("{}", headers), String.class);
    }

    @Test
    void aNameThatIsNotAnIdentifierGetsTheServersDefaultErrorAnswer() {
        for (String bad : new String[]{"1abc", "a-b", "a.b", "x".repeat(64)}) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.POST}) {
                ResponseEntity<String> answer = call(method, bad);

                assertThat(answer.getStatusCode().value()).as("%s %s", method, bad).isEqualTo(500);
                assertThat(answer.getHeaders().getContentType().toString()).startsWith("application/json");
                assertThat(answer.getBody()).contains("\"status\":500").contains("\"error\":\"Internal Server Error\"")
                        .contains("/test-proj/api/v1/rpc/");
            }
        }
    }
}
