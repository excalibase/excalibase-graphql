package io.github.excalibase.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.excalibase.cache.TTLCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CACHE_KEY = "public-key";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String provisioningUrl;
    private final TTLCache<String, ECPublicKey> keyCache;
    private final HttpClient httpClient;

    /** Production constructor — fetches key from vault on first verify, caches with TTL. */
    public JwtService(String provisioningUrl, int ttlMinutes) {
        this.provisioningUrl = provisioningUrl;
        this.keyCache = new TTLCache<>(Duration.ofMinutes(ttlMinutes));
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        if (provisioningUrl != null && !provisioningUrl.isBlank()) {
            try {
                keyCache.put(CACHE_KEY, fetchPublicKey());
                log.info("jwt_public_key_loaded source=vault");
            } catch (Exception e) {
                log.error("failed_to_fetch_jwt_public_key url={}", provisioningUrl, e);
                throw new IllegalStateException("Cannot start with jwt-enabled=true without vault public key", e);
            }
        }
    }

    /** Test constructor — inject key directly, no vault fetch. */
    public JwtService(ECPublicKey publicKey) {
        this.provisioningUrl = null;
        this.keyCache = new TTLCache<>(Duration.ofHours(24));
        this.httpClient = null;
        this.keyCache.put(CACHE_KEY, publicKey);
    }

    public JwtClaims verify(String token) {
        ECPublicKey key = getPublicKey();
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            ECDSAVerifier verifier = new ECDSAVerifier(key);

            if (!signedJWT.verify(verifier)) {
                throw new JwtVerificationException("JWT signature verification failed");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            Date expiration = claims.getExpirationTime();
            if (expiration != null && new Date().after(expiration)) {
                throw new JwtVerificationException("JWT token expired");
            }

            Object userIdClaim = claims.getClaim("userId");
            Long userId = userIdClaim instanceof Number n ? n.longValue() : null;
            String projectId = (String) claims.getClaim("projectId");
            String orgSlug = (String) claims.getClaim("orgSlug");
            String projectName = (String) claims.getClaim("projectName");
            String role = (String) claims.getClaim("role");
            String email = claims.getSubject();

            if (userId == null || projectId == null) {
                throw new JwtVerificationException("Missing required claims: userId or projectId");
            }

            return new JwtClaims(userId, projectId, orgSlug, projectName, role != null ? role : "user", email);
        } catch (JwtVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtVerificationException("Invalid JWT: " + e.getMessage(), e);
        }
    }

    private ECPublicKey getPublicKey() {
        return keyCache.computeIfAbsent(CACHE_KEY, k -> {
            try {
                log.info("jwt_public_key_refresh source=vault");
                return fetchPublicKey();
            } catch (Exception e) {
                throw new JwtVerificationException("Failed to refresh public key from vault", e);
            }
        });
    }

    private ECPublicKey fetchPublicKey() throws Exception {
        if (provisioningUrl == null || provisioningUrl.isBlank()) {
            throw new IllegalStateException("provisioning-url not configured");
        }
        String url = provisioningUrl + "/vault/pki/public-key";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(TIMEOUT)
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Vault returned " + resp.statusCode() + " for public key");
        }

        JsonNode json = mapper.readTree(resp.body());
        String pem = json.get("key").asText();
        return parseECPublicKey(pem);
    }

    private static ECPublicKey parseECPublicKey(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decoded);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(spec);
    }
}
