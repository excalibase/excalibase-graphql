package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EXC-726: a body is read into memory only up to 8 MiB; a bulk insert of the engine's 1000-row cap fits well inside. */
class RequestBodyLimitIntegrationTest extends EngineIntegrationTest {

    private static final String PROJECT = "test-proj";
    private static final String GRAPHQL = "/" + PROJECT + "/graphql";

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.unreachableDatabase(PROJECT);
    }

    private static final int MIB = 1024 * 1024;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String contentType, BodyPublisher body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .header("Content-Type", contentType).method(method, body).build(), HttpResponse.BodyHandlers.ofString());
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

    private static String body(int padBytes) {
        return "{\"query\":\"{ __typename }\",\"pad\":\"" + "x".repeat(padBytes) + "\"}";
    }

    @Test
    @DisplayName("a body of 9 MiB is refused with 413 before it is buffered")
    void oversizedBodyIsRefused() {
        HttpAnswer answer = http.post(GRAPHQL, body(9 * 1024 * 1024), "Content-Type", "application/json");

        assertThat(answer.status()).isEqualTo(413);
    }

    @Test
    @DisplayName("a body of 6 MiB, more than a 1000-row bulk insert, is still read")
    void largeLegitimateBodyIsServed() {
        HttpAnswer answer = http.post(GRAPHQL, body(6 * 1024 * 1024), "Content-Type", "application/json");

        assertThat(answer.status()).isNotEqualTo(413);
    }

    @Test
    @DisplayName("the 413 carries the security headers and Vary of any other error, in the default error body")
    void refusalLooksLikeOtherErrors() throws Exception {
        HttpResponse<String> response = send("POST", GRAPHQL, "application/json", BodyPublishers.ofString(body(9 * MIB)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("vary").orElse("")).contains("Origin");
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("\"status\":413").contains("Payload Too Large");
    }

    @Test
    @DisplayName("a REST JSON body of 9 MiB is refused with 413")
    void oversizedRestBodyIsRefused() throws Exception {
        assertThat(send("POST", "/" + PROJECT + "/api/v1/messages", "application/json",
                BodyPublishers.ofString(body(9 * MIB))).statusCode()).isEqualTo(413);
    }

    @Test
    @DisplayName("a chunked body of 9 MiB, with no announced length, is refused with 413")
    void oversizedChunkedBodyIsRefused() throws Exception {
        assertThat(send("POST", GRAPHQL, "application/json", chunked(body(9 * MIB))).statusCode()).isEqualTo(413);
    }

    @Test
    @DisplayName("a form body of 9 MiB is refused with 413 on GraphQL and REST")
    void oversizedFormBodyIsRefused() throws Exception {
        String form = "a=" + "x".repeat(9 * MIB);
        for (String path : List.of(GRAPHQL, "/" + PROJECT + "/api/v1/messages")) {
            assertThat(send("POST", path, "application/x-www-form-urlencoded", BodyPublishers.ofString(form)).statusCode())
                    .as(path).isEqualTo(413);
        }
    }

    @Test
    @DisplayName("a chunked 9 MiB form body on POST, PATCH, PUT and DELETE is refused with 413")
    void oversizedChunkedFormOnWriteMethodsIsRefused() throws Exception {
        String form = "a=" + "x".repeat(9 * MIB);
        for (String method : List.of("POST", "PATCH", "PUT", "DELETE")) {
            assertThat(send(method, "/" + PROJECT + "/api/v1/messages?id=eq.1", "application/x-www-form-urlencoded",
                    chunked(form)).statusCode()).as(method).isEqualTo(413);
        }
        assertThat(send("POST", GRAPHQL, "application/x-www-form-urlencoded", chunked(form)).statusCode())
                .as("POST graphql").isEqualTo(413);
    }

    @Test
    @DisplayName("a chunked body of 6 MiB is still read")
    void largeChunkedBodyIsServed() throws Exception {
        assertThat(send("POST", GRAPHQL, "application/json", chunked(body(6 * MIB))).statusCode()).isNotEqualTo(413);
    }
}
