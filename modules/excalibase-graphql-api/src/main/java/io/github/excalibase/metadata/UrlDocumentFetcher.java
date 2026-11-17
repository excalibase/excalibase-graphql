package io.github.excalibase.metadata;

import io.github.excalibase.http.ControlPlaneHttpClient;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the metadata document over HTTP(S), read-only. The version is the server's {@code ETag} when it sends
 * one (and the next poll asks with {@code If-None-Match}, so an unchanged document costs a 304), otherwise a hash
 * of the body. Nothing here decides what is good: any failure is an {@link IOException} and the reload loop keeps
 * the last good version. The token is sent only to the configured origin and is never part of a message.
 */
final class UrlDocumentFetcher implements ReloadingMetadataSource.Loader {

    private static final int MAX_REDIRECTS = 3;

    private final HttpClient http;
    private final URI location;
    private final TokenFileSource token;
    private final Duration readTimeout;
    private ReloadingMetadataSource.Raw last;
    private String lastEtag;

    UrlDocumentFetcher(URI location, TokenFileSource token, Duration connectTimeout, Duration readTimeout) {
        this.http = ControlPlaneHttpClient.create(connectTimeout);
        this.location = location;
        this.token = token;
        this.readTimeout = readTimeout;
    }

    @Override
    public ReloadingMetadataSource.Raw load() throws IOException {
        URI target = location;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = send(target);
            Optional<URI> next = redirectTarget(target, response);
            if (next.isEmpty()) {
                return interpret(response);
            }
            response.body().close();
            target = next.get();
        }
        throw new IOException("too many redirects");
    }

    private HttpResponse<InputStream> send(URI target) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(readTimeout).GET();
        if (lastEtag != null) {
            request.header("If-None-Match", lastEtag);
        }
        String bearer = bearerToken();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching the document");
        }
    }

    private String bearerToken() throws IOException {
        if (token == null) {
            return null;
        }
        try {
            return token.require();
        } catch (TokenUnavailableException e) {
            throw new IOException("the token is not available: " + e.getMessage());
        }
    }

    /** A redirect is followed only within the same scheme, host and port; the document never moves the token elsewhere. */
    private static Optional<URI> redirectTarget(URI from, HttpResponse<?> response) throws IOException {
        int status = response.statusCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return Optional.empty();
        }
        URI to = response.headers().firstValue("Location").map(from::resolve)
                .orElseThrow(() -> new IOException("redirect without a Location"));
        if (!sameOrigin(from, to)) {
            throw new IOException("redirect to another host refused (" + to.getHost() + ")");
        }
        return Optional.of(to);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private ReloadingMetadataSource.Raw interpret(HttpResponse<InputStream> response) throws IOException {
        int status = response.statusCode();
        if (status == 304 && last != null) {
            response.body().close();
            return last;
        }
        if (status != 200) {
            response.body().close();
            throw new IOException("the server answered HTTP " + status);
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
        if (declared > MetadataDocumentParser.MAX_BYTES) {
            response.body().close();
            throw new IOException("the document is larger than " + MetadataDocumentParser.MAX_BYTES + " bytes");
        }
        byte[] bytes = readCapped(response.body());
        String etag = response.headers().firstValue("ETag").orElse(null);
        last = new ReloadingMetadataSource.Raw(FileMetadataSource.decodeUtf8(bytes),
                etag == null ? "sha256:" + FileMetadataSource.sha256(bytes) : "etag:" + etag);
        lastEtag = etag;
        return last;
    }

    /**
     * Reads at most one byte over the limit and then stops, so an endless body costs the limit and not the
     * server's whole answer. The read runs against a deadline because the request timeout ends at the headers.
     */
    private byte[] readCapped(InputStream body) throws IOException {
        CompletableFuture<byte[]> read = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (body) {
                read.complete(body.readNBytes(MetadataDocumentParser.MAX_BYTES + 1));
            } catch (IOException | RuntimeException e) {
                read.completeExceptionally(e);
            }
        });
        byte[] bytes = awaitBody(read, body);
        if (bytes.length > MetadataDocumentParser.MAX_BYTES) {
            throw new IOException("the document is larger than " + MetadataDocumentParser.MAX_BYTES + " bytes");
        }
        return bytes;
    }

    private byte[] awaitBody(CompletableFuture<byte[]> read, InputStream body) throws IOException {
        try {
            return read.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            body.close();
            throw new SocketTimeoutException("reading the document timed out");
        } catch (InterruptedException _) {
            body.close();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the document");
        } catch (ExecutionException e) {
            throw new IOException("reading the document failed: " + e.getCause().getClass().getSimpleName());
        }
    }
}
