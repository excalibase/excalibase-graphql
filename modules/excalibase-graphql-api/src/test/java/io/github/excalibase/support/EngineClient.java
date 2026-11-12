package io.github.excalibase.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.config.RequestTargetEncoding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;

/** Talks to the engine over a real socket, so tests see exactly what a client sees. */
public final class EngineClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final URI base;
    private final RawHttpClient raw;

    public EngineClient(URI base) {
        this.base = base;
        this.raw = new RawHttpClient(base);
    }

    public URI base() {
        return base;
    }

    public HttpAnswer get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    /** A GET whose target is sent exactly as written, braces, quotes and a lone percent sign included. */
    public HttpAnswer getUnencoded(String path) {
        return raw.get(path);
    }

    public HttpAnswer delete(String path, String... headers) {
        return send("DELETE", path, null, headers);
    }

    public HttpAnswer post(String path, String body, String... headers) {
        return send("POST", path, body, headers);
    }

    public HttpAnswer postJson(String path, Object body, String... headers) {
        return send("POST", path, json(body), withContentType(headers));
    }

    public HttpAnswer patchJson(String path, Object body, String... headers) {
        return send("PATCH", path, json(body), withContentType(headers));
    }

    public HttpAnswer putJson(String path, Object body, String... headers) {
        return send("PUT", path, json(body), withContentType(headers));
    }

    public HttpAnswer graphql(String path, String query, String... headers) {
        return postJson(path, Map.of("query", query), headers);
    }

    /** {@code headers} alternate name, value. */
    public HttpAnswer send(String method, String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + RequestTargetEncoding.encode(path.replaceFirst("^/", ""))))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return HttpAnswer.of(client.send(request.build(), BodyHandlers.ofString()));
        } catch (IOException failure) {
            throw new IllegalStateException(method + " " + path + " failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " interrupted", interrupted);
        }
    }

    private static String json(Object body) {
        try {
            return body instanceof String text ? text : MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static String[] withContentType(String[] headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "Content-Type";
        all[1] = "application/json";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return all;
    }
}
