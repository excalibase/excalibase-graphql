package io.github.excalibase.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Value("${app.security.provisioning-url:}")
    private String provisioningUrl;

    private ECPublicKey publicKey;

    /** Constructor for production — key fetched in @PostConstruct */
    public JwtService() {}

    /** Constructor for tests — inject key directly */
    public JwtService(ECPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @PostConstruct
    void init() {
        if (publicKey != null) return; // already set (test constructor)
        if (provisioningUrl == null || provisioningUrl.isBlank()) {
            log.warn("JWT enabled but no provisioning-url configured — JWT verification disabled");
            return;
        }
        try {
            publicKey = fetchPublicKey(provisioningUrl);
            log.info("JWT verification enabled — public key loaded from vault");
        } catch (Exception e) {
            log.error("Failed to fetch JWT public key from vault: {}", e.getMessage());
            throw new IllegalStateException("Cannot start with jwt-enabled=true without vault public key", e);
        }
    }

    public JwtClaims verify(String token) {
        if (publicKey == null) {
            throw new JwtVerificationException("JWT verification not initialized");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = claims.get("userId", Long.class);
            String projectId = claims.get("projectId", String.class);
            String role = claims.get("role", String.class);
            String email = claims.getSubject();

            if (userId == null || projectId == null) {
                throw new JwtVerificationException("Missing required claims: userId or projectId");
            }

            return new JwtClaims(userId, projectId, role != null ? role : "user", email);
        } catch (Exception e) {
            throw new JwtVerificationException("Invalid JWT: " + e.getMessage(), e);
        }
    }

    private static ECPublicKey fetchPublicKey(String provisioningUrl) throws Exception {
        String url = provisioningUrl + "/vault/pki/public-key";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Vault returned " + resp.statusCode() + " for public key");
        }

        JsonNode json = new ObjectMapper().readTree(resp.body());
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
