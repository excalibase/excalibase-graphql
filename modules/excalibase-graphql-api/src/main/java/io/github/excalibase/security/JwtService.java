package io.github.excalibase.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.cache.TTLCache;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

@Service
@ConditionalOnProperty(name = "app.security.jwt-enabled", havingValue = "true")
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CACHE_KEY = "public-key";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String provisioningUrl;
    private final TTLCache<String, ECPublicKey> keyCache;
    private final HttpClient httpClient;

    /** Production constructor — fetches key from vault on first verify, caches with TTL. */
    @Autowired
    public JwtService(
            @Value("${app.security.provisioning-url:}") String provisioningUrl,
            @Value("${app.cache.schema-ttl-minutes:30}") int ttlMinutes) {
        this.provisioningUrl = provisioningUrl;
        this.keyCache = new TTLCache<>(Duration.ofMinutes(ttlMinutes));
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        // Eagerly load key at startup to fail fast if vault is unreachable
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
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = claims.get("userId", Long.class);
            String projectId = claims.get("projectId", String.class);
            String orgSlug = claims.get("orgSlug", String.class);
            String projectName = claims.get("projectName", String.class);
            String role = claims.get("role", String.class);
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
