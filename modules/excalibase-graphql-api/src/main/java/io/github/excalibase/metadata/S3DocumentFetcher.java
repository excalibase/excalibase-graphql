package io.github.excalibase.metadata;

import io.github.excalibase.http.ControlPlaneHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads the metadata object with one SigV4-signed GET per poll: no listing, no multipart, no SDK. The version is
 * the hash of the body, as for the url source: an ETag is not trusted to change exactly when the bytes do (an
 * S3-compatible store may derive it differently), and a 304 would settle a body the engine has not read twice.
 * Redirects are never followed, so the signature goes only to the configured endpoint. A failure is an
 * {@link IOException} carrying a status or an exception class and nothing else: the store's error document can
 * name the access key, so it is never read, and neither a secret nor a configured value is part of a message.
 */
final class S3DocumentFetcher implements ReloadingMetadataSource.Loader {

    private static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final HttpClient http;
    private final URI object;
    private final S3Settings settings;
    private final Duration readTimeout;

    S3DocumentFetcher(URI object, S3Settings settings) {
        this.http = ControlPlaneHttpClient.create(settings.connectTimeout());
        this.object = object;
        this.settings = settings;
        this.readTimeout = settings.readTimeout();
    }

    /** The document and the ETag the store gave it, which a conditional write quotes back. */
    record Fetched(ReloadingMetadataSource.Raw raw, String etag) {
    }

    @Override
    public ReloadingMetadataSource.Raw load() throws IOException {
        return fetch().orElseThrow(() -> new IOException(describe(404))).raw();
    }

    /** @return empty when the object does not exist (a 404) */
    Optional<Fetched> fetch() throws IOException {
        S3Credentials.Resolved keys = S3Credentials.load(settings.credentials());
        try {
            return interpret(send(keys));
        } catch (RuntimeException e) {
            throw new IOException("the request or response was malformed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private HttpResponse<InputStream> send(S3Credentials.Resolved keys) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(object).timeout(readTimeout).GET();
        SigV4.sign("GET", object, EMPTY_BODY_SHA256, keys, settings.region().trim(), Instant.now())
                .forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching the document");
        } catch (IOException e) {
            throw new IOException("the store could not be reached (" + e.getClass().getSimpleName() + ")");
        }
    }

    private Optional<Fetched> interpret(HttpResponse<InputStream> response) throws IOException {
        int status = response.statusCode();
        if (status == 404) {
            response.body().close();
            return Optional.empty();
        }
        if (status != 200) {
            response.body().close();
            throw new IOException(describe(status));
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
        if (declared > MetadataDocumentParser.MAX_BYTES) {
            response.body().close();
            throw new IOException(BoundedBody.tooLarge());
        }
        byte[] bytes = BoundedBody.read(response.body(), readTimeout);
        return Optional.of(new Fetched(new ReloadingMetadataSource.Raw(FileMetadataSource.decodeUtf8(bytes),
                "sha256:" + FileMetadataSource.sha256(bytes)), response.headers().firstValue("ETag").orElse(null)));
    }

    private static String describe(int status) {
        return switch (status) {
            case 403 -> "the store answered HTTP 403: the credentials or the bucket policy refuse this read";
            case 404 -> "the store answered HTTP 404: the bucket or the object does not exist";
            case 301, 302, 307, 308 -> "the store answered HTTP " + status + ", a redirect that is not followed: "
                    + "check metadata.s3.region and metadata.s3.endpoint";
            default -> "the store answered HTTP " + status;
        };
    }
}
