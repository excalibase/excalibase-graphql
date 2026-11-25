package io.github.excalibase.metadata;

import io.github.excalibase.http.ControlPlaneHttpClient;
import io.github.excalibase.security.TokenFileSource;
import io.github.excalibase.security.TokenUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Reads the metadata document over HTTP(S), read-only. Every poll is a plain full GET (the 1 MiB cap keeps it
 * cheap) and the version is the hash of the body, never the server's {@code ETag} and never a {@code 304}: an ETag
 * can stay the same while the body changes (a same-second edit) or change while the body does not, and a 304 would
 * settle a body the engine has not read twice. The reload loop therefore sees exactly the bytes it judges.
 * Nothing here decides what is good: any failure, including a malformed response header, is an
 * {@link IOException} and the loop keeps the last good version. The token is sent only to the configured origin
 * and is never part of a message.
 */
final class UrlDocumentFetcher implements ReloadingMetadataSource.Loader {

    private static final int MAX_REDIRECTS = 3;

    private final HttpClient http;
    private final URI location;
    private final TokenFileSource token;
    private final Duration readTimeout;

    UrlDocumentFetcher(URI location, TokenFileSource token, Duration connectTimeout, Duration readTimeout) {
        this.http = ControlPlaneHttpClient.create(connectTimeout);
        this.location = location;
        this.token = token;
        this.readTimeout = readTimeout;
    }

    /** Any failure surfaces as an IOException with no response or token text in it. */
    @Override
    public ReloadingMetadataSource.Raw load() throws IOException {
        try {
            return fetch();
        } catch (RuntimeException e) {
            throw new IOException("the response or request was malformed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private ReloadingMetadataSource.Raw fetch() throws IOException {
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
        String value;
        try {
            value = token.require().trim();
        } catch (TokenUnavailableException _) {
            throw new IOException("no bearer token is available: set metadata.url.token or metadata.url.token-file");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("the bearer token contains a control character (such as a newline in the middle)");
        }
        return value;
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
        if (status != 200) {
            response.body().close();
            throw new IOException("the server answered HTTP " + status);
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
        if (declared > MetadataDocumentParser.MAX_BYTES) {
            response.body().close();
            throw new IOException(BoundedBody.tooLarge());
        }
        byte[] bytes = BoundedBody.read(response.body(), readTimeout);
        return new ReloadingMetadataSource.Raw(FileMetadataSource.decodeUtf8(bytes),
                "sha256:" + FileMetadataSource.sha256(bytes));
    }
}
