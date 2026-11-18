package io.github.excalibase.metadata;

import io.github.excalibase.http.ControlPlaneHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.TreeMap;

/**
 * The signed writes the {@code s3} source makes: a PUT that may carry a precondition, and a DELETE. They are signed
 * like the read (see {@link S3DocumentFetcher}): the credentials are read afresh each time, redirects are never
 * followed, the response body is never read and a failure names a status or an exception class and nothing else.
 */
final class S3Http {

    private final HttpClient http;
    private final S3Settings settings;

    S3Http(S3Settings settings) {
        this.http = ControlPlaneHttpClient.create(settings.connectTimeout());
        this.settings = settings;
    }

    /**
     * @param condition a header name and value ({@code If-Match: "<etag>"} or {@code If-None-Match: *}), or null for
     *                  an unconditional PUT
     * @return the HTTP status
     */
    int put(URI object, String body, Condition condition) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        TreeMap<String, String> conditions = new TreeMap<>();
        if (condition != null) {
            conditions.put(condition.header().toLowerCase(Locale.ROOT), condition.value());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(object).timeout(settings.readTimeout())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
        return send("PUT", object, FileMetadataSource.sha256(payload), conditions, request, condition);
    }

    int delete(URI object) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(object).timeout(settings.readTimeout()).DELETE();
        return send("DELETE", object, FileMetadataSource.sha256(new byte[0]), new TreeMap<>(), request, null);
    }

    record Condition(String header, String value) {
        static Condition ifMatch(String etag) {
            return new Condition("If-Match", etag);
        }

        static Condition ifNoneMatch() {
            return new Condition("If-None-Match", "*");
        }
    }

    private int send(String method, URI object, String payloadSha256, TreeMap<String, String> signedHeaders,
                     HttpRequest.Builder request, Condition condition) throws IOException {
        S3Credentials.Resolved keys = S3Credentials.load(settings.credentials());
        SigV4.sign(method, object, payloadSha256, keys, settings.region().trim(), Instant.now(), signedHeaders)
                .forEach(request::header);
        if (condition != null) {
            request.header(condition.header(), condition.value());
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while writing the document");
        } catch (IOException e) {
            throw new IOException("the store could not be reached (" + e.getClass().getSimpleName() + ")");
        } catch (RuntimeException e) {
            throw new IOException("the request was malformed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
