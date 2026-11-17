package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import io.github.excalibase.security.JwksKeys;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
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

    private static URI discover(String issuer) {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        try {
            String body = new DefaultResourceRetriever(TIMEOUT_MILLIS, TIMEOUT_MILLIS, SIZE_LIMIT_BYTES)
                    .retrieveResource(URI.create(base + DISCOVERY_PATH).toURL()).getContent();
            JsonNode metadata = JSON.readTree(body);
            if (!issuer.equals(metadata.path("issuer").asText())) {
                throw new IllegalStateException("the discovery document names another issuer than the jwt settings");
            }
            return JwtSettingsParser.requireHttpsUrl(metadata.path("jwks_uri").asText(""), "discovered jwks_uri");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the issuer's OIDC discovery document: " + e, e);
        }
    }

    private static List<JwksKeys.Key> fetch(URI jwks) throws IOException, ParseException, JOSEException {
        JWKSet set = JWKSet.load(jwks.toURL(), TIMEOUT_MILLIS, TIMEOUT_MILLIS, SIZE_LIMIT_BYTES);
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
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the jwt secret file: " + e.getClass().getSimpleName(), e);
        }
    }
}
