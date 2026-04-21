package io.github.excalibase.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceHmacTest {

    private static final String SECRET = "this-secret-must-be-at-least-32-chars-long";
    private static final String SECRET2 = "a-different-32-char-secret-for-tests-xyz";

    private String signHmac(String secret, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes()));
        return jwt.serialize();
    }

    @Test
    @DisplayName("HMAC constructor rejects a short secret")
    void hmacCtor_shortSecret_throws() {
        assertThatThrownBy(() -> new JwtService("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    @DisplayName("HMAC constructor rejects a null secret")
    void hmacCtor_nullSecret_throws() {
        assertThatThrownBy(() -> new JwtService((String) null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("HMAC-signed token with valid signature verifies successfully")
    void hmac_validToken_verifies() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.com")
                .claim("userId", 42L)
                .claim("projectId", "p1")
                .claim("role", "admin")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String token = signHmac(SECRET, claims);

        JwtClaims result = svc.verify(token);

        assertThat(result.userId()).isEqualTo("42");
        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.role()).isEqualTo("admin");
    }

    @Test
    @DisplayName("HMAC token signed with a different secret fails signature verification")
    void hmac_wrongSecret_throws() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.com")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String token = signHmac(SECRET2, claims);

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("signature verification failed");
    }

    @Test
    @DisplayName("token without exp claim fails verification for regular users")
    void tokenMissingExp_throws() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.com")
                .claim("userId", 1L)
                .build();
        String token = signHmac(SECRET, claims);

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("missing required 'exp'");
    }

    @Test
    @DisplayName("api-key scoped token may omit exp claim")
    void apiKeyToken_noExp_isAccepted() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("bot@example.com")
                .claim("userId", 7L)
                .claim("scope", "api-key")
                .claim("keyId", 99L)
                .build();
        String token = signHmac(SECRET, claims);

        JwtClaims result = svc.verify(token);

        assertThat(result.scope()).isEqualTo("api-key");
        assertThat(result.keyId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("expired token fails verification")
    void expiredToken_throws() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.com")
                .claim("userId", 1L)
                .expirationTime(Date.from(Instant.now().minusSeconds(10)))
                .build();
        String token = signHmac(SECRET, claims);

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("not-yet-valid (nbf in future) token fails verification")
    void notBeforeInFuture_throws() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.com")
                .claim("userId", 1L)
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .notBeforeTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        String token = signHmac(SECRET, claims);

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("not yet valid");
    }

    @Test
    @DisplayName("token without userId falls back to sub claim")
    void noUserIdClaim_fallsBackToSub() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("subject-fallback")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String token = signHmac(SECRET, claims);

        JwtClaims result = svc.verify(token);

        assertThat(result.userId()).isEqualTo("subject-fallback");
    }

    @Test
    @DisplayName("token with neither userId nor sub throws")
    void noUserIdAndNoSub_throws() throws Exception {
        JwtService svc = new JwtService(SECRET);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        String token = signHmac(SECRET, claims);

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("userId or sub");
    }

    @Test
    @DisplayName("malformed token string throws JwtVerificationException")
    void malformedToken_throws() {
        JwtService svc = new JwtService(SECRET);

        assertThatThrownBy(() -> svc.verify("not-a-real-jwt"))
                .isInstanceOf(JwtVerificationException.class);
    }
}
