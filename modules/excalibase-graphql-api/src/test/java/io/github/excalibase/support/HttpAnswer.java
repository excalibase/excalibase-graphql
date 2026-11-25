package io.github.excalibase.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;

/** What a real server answered: status, body text and headers. Never throws on a 4xx or 5xx. */
public record HttpAnswer(int status, String body, HttpHeaders headers) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static HttpAnswer of(HttpResponse<String> response) {
        return new HttpAnswer(response.statusCode(), response.body(), response.headers());
    }

    public JsonNode json() {
        try {
            return MAPPER.readTree(body);
        } catch (Exception notJson) {
            throw new IllegalStateException("not JSON (status " + status + "): " + body, notJson);
        }
    }

    /** The value at {@code path} in the body, typed by the caller; fails when the path leads nowhere. */
    @SuppressWarnings("unchecked")
    public <T> T jsonPath(String path) {
        return (T) JsonPaths.select(json(), path).value()
                .orElseThrow(() -> new AssertionError("No value at JSON path " + path + " in " + body));
    }

    /** Runs each check against this answer; the first one that fails stops the test. */
    public HttpAnswer expect(Expectation... expectations) {
        for (Expectation expectation : expectations) {
            expectation.check(this);
        }
        return this;
    }

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }
}
