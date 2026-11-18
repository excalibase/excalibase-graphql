package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.github.excalibase.security.JwksKeys;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Gets what a token is checked against: the identity provider's keys (found by OIDC discovery when the document
 * names only the issuer) or a shared secret read from the environment or a file. Every failure is an
 * {@link IllegalStateException}, not a document error: the document may be fine and the provider briefly
 * unreachable, so the reload tries again instead of judging the document bad.
 */
final class JwtKeyLoader {

    private static final int TIMEOUT_MILLIS = 3_000;
    private static final int SIZE_LIMIT_BYTES = 64 * 1024;
    private static final Duration KEY_TTL = Duration.ofMinutes(15);
    /** A failed fetch, and a token naming a key we do not hold, wait this long before reaching the provider again. */
    private static final Duration MIN_RETRY = Duration.ofSeconds(10);
    private static final int MIN_RSA_BITS = 2048;
    private static final int MIN_SECRET_BYTES = 32;
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "jwt-keys-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(TIMEOUT_MILLIS)).build();

    private JwtKeyLoader() {
    }

    /** Fetches the keys once, now; later refreshes keep the last good ones when the provider fails. */
    static JwksKeys keys(JwtSettings settings, LongSupplier clock) {
        URI jwks = settings.jwksUrl() != null ? URI.create(settings.jwksUrl()) : discover(settings.issuer());
        JwksKeys.Fetcher fetcher = () -> fetch(jwks);
        try {
            return new JwksKeys(fetcher, KEY_TTL, MIN_RETRY, clock, fetcher.fetch());
        } catch (Exception e) {
            throw new IllegalStateException("cannot load the signing keys from the jwt settings: " + e, e);
        }
    }

    /** The key set a discovery document names must be as secure as the issuer that named it. */
    static URI checkedJwksUri(String issuer, String jwksUri) {
        URI uri = JwtSettingsParser.requireHttpsUrl(jwksUri, "discovered jwks_uri");
        if (issuer.regionMatches(true, 0, "https:", 0, "https:".length()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("the discovered jwks_uri is plain http although the issuer is https");
        }
        return uri;
    }

    /**
     * A plain GET that follows no redirect, and that is over {@value #TIMEOUT_MILLIS} ms after it started, body
     * included: a server that sends headers and then stalls or trickles is cut off, not waited for.
     */
    private static String get(URI uri) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        CompletableFuture<HttpResponse<InputStream>> exchange =
                HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = exchange.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).body()) {
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> cutOff = WATCHDOG.schedule(() -> closeAfterTimeout(body, timedOut),
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                return readBody(exchange.get().statusCode(), body, timedOut);
            } finally {
                cutOff.cancel(false);
            }
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new IOException("the provider did not answer in time", e);
        } catch (ExecutionException e) {
            throw new IOException("the provider could not be reached: " + e.getCause(), e);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading from the provider");
        }
    }

    private static String readBody(int status, InputStream body, AtomicBoolean timedOut) throws IOException {
        if (status != 200) {
            throw new IOException("the provider answered HTTP " + status);
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(SIZE_LIMIT_BYTES + 1);
        } catch (IOException e) {
            throw timedOut.get() ? new IOException("the provider did not finish its answer in time", e) : e;
        }
        if (timedOut.get()) {
            throw new IOException("the provider did not finish its answer in time");
        }
        if (bytes.length > SIZE_LIMIT_BYTES) {
            throw new IOException("the answer is larger than " + SIZE_LIMIT_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void closeAfterTimeout(InputStream body, AtomicBoolean timedOut) {
        timedOut.set(true);
        try {
            body.close();
        } catch (IOException _) {
            // closing is only how the blocked read is woken; the reader reports the timeout
        }
    }

    private static URI discover(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try {
            JsonNode metadata = JSON.readTree(get(URI.create(base + DISCOVERY_PATH)));
            if (!issuer.equals(metadata.path("issuer").asText())) {
                throw new IllegalStateException("the discovery document names another issuer than the jwt settings");
            }
            return checkedJwksUri(issuer, metadata.path("jwks_uri").asText(""));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the issuer's OIDC discovery document: " + e, e);
        }
    }

    private static List<JwksKeys.Key> fetch(URI jwks) throws IOException, ParseException, JOSEException {
        JWKSet set = JWKSet.parse(get(jwks));
        List<JwksKeys.Key> keys = new ArrayList<>();
        for (JWK jwk : set.getKeys()) {
            if (jwk.getKeyUse() == KeyUse.ENCRYPTION) {
                continue;
            }
            if (jwk instanceof RSAKey rsa && rsa.size() >= MIN_RSA_BITS) {
                keys.add(new JwksKeys.Key(rsa.getKeyID(), rsa.toRSAPublicKey()));
            } else if (jwk instanceof ECKey ec) {
                keys.add(new JwksKeys.Key(ec.getKeyID(), ec.toECPublicKey()));
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("the key set holds no usable RSA (2048 bits or more) or EC key");
        }
        return keys;
    }

    /** @param reference {@code env:NAME} or {@code file:/path}, already validated by the parser */
    static byte[] secret(String reference, UnaryOperator<String> environment) {
        String value;
        if (reference.startsWith("env:")) {
            value = environment.apply(reference.substring("env:".length()));
        } else {
            value = readSecretFile(Path.of(reference.substring("file:".length())));
        }
        byte[] secret = value == null ? new byte[0] : value.strip().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("the jwt secret is missing or shorter than " + MIN_SECRET_BYTES + " bytes");
        }
        return secret;
    }

    private static String readSecretFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("the jwt secret file is not a regular file");
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(SIZE_LIMIT_BYTES + 1);
            if (bytes.length > SIZE_LIMIT_BYTES) {
                throw new IllegalStateException("the jwt secret file is larger than " + SIZE_LIMIT_BYTES + " bytes");
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the jwt secret file: " + e.getClass().getSimpleName(), e);
        }
    }
}
