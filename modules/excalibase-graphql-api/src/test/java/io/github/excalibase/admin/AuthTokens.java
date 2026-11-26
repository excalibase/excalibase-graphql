package io.github.excalibase.admin;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

/** Tokens signed the way an identity provider would, for the engine's own token settings. */
final class AuthTokens {

    static final String SECRET = "an-hmac-secret-of-at-least-32-bytes!!";
    static final String OTHER_SECRET = "another-hmac-secret-of-32-bytes-or-more";
    static final String AUDIENCE = "my-api";
    static final String ISSUER = "https://idp.example.com/";

    private AuthTokens() {
    }

    /** A good token for user {@code u-1}; the caller changes what it wants to break. */
    static JWTClaimsSet.Builder good() {
        return new JWTClaimsSet.Builder().subject("u-1").issuer(ISSUER).audience(AUDIENCE).claim("role", "user")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }

    static String sign(JWTClaimsSet.Builder claims) {
        return sign(claims, JWSAlgorithm.HS256, SECRET);
    }

    static String sign(JWTClaimsSet.Builder claims, JWSAlgorithm algorithm, String secret) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(Consumer<JWTClaimsSet.Builder> change) {
        JWTClaimsSet.Builder claims = good();
        change.accept(claims);
        return sign(claims);
    }

    /** A token whose header says {@code alg: none} and that has no signature. */
    static String unsigned() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encoder.encodeToString(
                ("{\"sub\":\"u-1\",\"aud\":\"" + AUDIENCE + "\",\"role\":\"user\",\"exp\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }
}
