package io.github.excalibase.security;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.cache.TTLCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;

/**
 * Verifies JWTs. Two modes:
 *
 *   JWKS  — ES256, fetches public keys from a /.well-known/jwks.json endpoint.
 *           Use with excalibase-auth (provisioning mode) or any OIDC provider.
 *
 *   HMAC  — HS256, shared secret between auth and graphql.
 *           Use for standalone deployments without excalibase-auth.
 *
 * Claims extracted (both modes):
 *   userId, projectId, orgSlug, projectName, role, email/sub
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CACHE_KEY = "jwks";

    // JWKS mode
    private final String jwksUrl;
    private final TTLCache<String, List<ECPublicKey>> keyCache;

    // HMAC mode
    private final byte[] hmacSecret;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** JWKS mode — fetches EC public keys from the given URL, caches with TTL. */
    public JwtService(String jwksUrl, int ttlMinutes) {
        this.jwksUrl = jwksUrl;
        this.keyCache = new TTLCache<>(Duration.ofMinutes(ttlMinutes));
        this.hmacSecret = null;

        try {
            keyCache.put(CACHE_KEY, fetchKeys());
            log.info("jwt_keys_loaded jwks_url={}", jwksUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start with jwt-enabled=true — failed to load JWKS from: " + jwksUrl, e);
        }
    }

    /** HMAC mode — verifies HS256 tokens using the given secret. */
    public JwtService(String hmacSecret) {
        if (hmacSecret == null || hmacSecret.length() < 32) {
            throw new IllegalStateException("app.security.auth.hmac-secret must be at least 32 characters");
        }
        this.hmacSecret = hmacSecret.getBytes(StandardCharsets.UTF_8);
        this.jwksUrl = null;
        this.keyCache = null;
        log.info("jwt_service_mode=hmac");
    }

    /** Test constructor — inject EC key directly, no remote fetch. */
    public JwtService(ECPublicKey publicKey) {
        this.jwksUrl = "test";
        this.keyCache = new TTLCache<>(Duration.ofHours(24));
        this.keyCache.put(CACHE_KEY, List.of(publicKey));
        this.hmacSecret = null;
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    public JwtClaims verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            if (hmacSecret != null) {
                verifyHmac(jwt);
            } else {
                verifyEc(jwt);
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            Date expiration = claims.getExpirationTime();
            if (expiration != null && new Date().after(expiration)) {
                throw new JwtVerificationException("JWT token expired");
            }

            String userId = extractUserId(claims);
            String projectId = (String) claims.getClaim("projectId");
            String orgSlug = (String) claims.getClaim("orgSlug");
            String projectName = (String) claims.getClaim("projectName");
            String role = claims.getClaim("role") instanceof String r ? r : "user";
            String email = claims.getSubject() != null ? claims.getSubject()
                    : (String) claims.getClaim("email");
            // Optional claims emitted by excalibase-auth's api-key grant.
            // Absent on password/refresh tokens — defaulted to null / 0.
            String scope = claims.getClaim("scope") instanceof String s ? s : null;
            long keyId = 0L;
            Object keyIdClaim = claims.getClaim("keyId");
            if (keyIdClaim instanceof Number n) {
                keyId = n.longValue();
            }

            return new JwtClaims(userId, projectId, orgSlug, projectName, role, email, scope, keyId);

        } catch (JwtVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtVerificationException("Invalid JWT: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void verifyHmac(SignedJWT jwt) throws JwtVerificationException, com.nimbusds.jose.JOSEException {
        if (!jwt.verify(new MACVerifier(hmacSecret))) {
            throw new JwtVerificationException("JWT signature verification failed");
        }
    }

    private void verifyEc(SignedJWT jwt) throws JwtVerificationException, com.nimbusds.jose.JOSEException {
        List<ECPublicKey> keys = getKeys();
        for (ECPublicKey key : keys) {
            if (jwt.verify(new ECDSAVerifier(key))) {
                return;
            }
        }
        throw new JwtVerificationException("JWT signature verification failed");
    }

    private String extractUserId(JWTClaimsSet claims) {
        Object userIdClaim = claims.getClaim("userId");
        if (userIdClaim != null) {
            return String.valueOf(userIdClaim);
        }
        String sub = claims.getSubject();
        if (sub != null && !sub.isBlank()) {
            return sub;
        }
        throw new JwtVerificationException("Missing required claim: userId or sub");
    }

    private List<ECPublicKey> getKeys() {
        return keyCache.computeIfAbsent(CACHE_KEY, k -> {
            try {
                log.info("jwt_keys_refresh jwks_url={}", jwksUrl);
                return fetchKeys();
            } catch (Exception e) {
                throw new JwtVerificationException("Failed to refresh JWKS from: " + jwksUrl, e);
            }
        });
    }

    private List<ECPublicKey> fetchKeys() throws java.io.IOException, java.text.ParseException, java.net.URISyntaxException {
        JWKSet jwkSet = JWKSet.load(new java.net.URI(jwksUrl).toURL());
        List<ECPublicKey> ecKeys = jwkSet.getKeys().stream()
                .filter(ECKey.class::isInstance)
                .map(k -> {
                    try {
                        return ((ECKey) k).toECPublicKey();
                    } catch (com.nimbusds.jose.JOSEException e) {
                        throw new JwtVerificationException("Failed to parse EC key from JWKS", e);
                    }
                })
                .toList();
        if (ecKeys.isEmpty()) {
            throw new IllegalStateException("No EC keys found in JWKS at: " + jwksUrl);
        }
        return ecKeys;
    }
}
