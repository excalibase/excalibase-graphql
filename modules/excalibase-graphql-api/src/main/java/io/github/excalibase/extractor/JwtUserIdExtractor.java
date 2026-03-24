package io.github.excalibase.extractor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.annotation.ExcalibaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts user ID and claims from a JWT Bearer token.
 *
 * Config:
 *   app.security.user-id-extractor-type: jwt
 *
 * Request:
 *   Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
 *
 * Extracted PostgreSQL session variables:
 *   request.user_id          = value of "sub" claim (falls back to "user_id" claim)
 *   request.jwt.role         = value of "role" claim
 *   request.jwt.email        = value of "email" claim
 *   request.jwt.tenant_id    = value of "tenant_id" claim
 *   ... (all other string-valued claims)
 *
 * RLS policy example:
 *   CREATE POLICY user_isolation ON orders
 *   FOR ALL USING (user_id = current_setting('request.user_id', true));
 *
 *   CREATE POLICY tenant_isolation ON documents
 *   FOR ALL USING (tenant_id = current_setting('request.jwt.tenant_id', true));
 *
 * Note: This decoder reads claims from the JWT payload without verifying the
 * signature. Signature verification should be handled by an upstream API gateway
 * or a Spring Security filter before this extractor runs.
 */
@Service
@ExcalibaseService(serviceName = "jwt")
public class JwtUserIdExtractor implements IUserIdExtractor {
    private static final Logger log = LoggerFactory.getLogger(JwtUserIdExtractor.class);
    private static final String BEARER_PREFIX = "Bearer ";
    // Ordered: "sub" is checked first (standard JWT), falls back to "user_id"
    private static final List<String> USER_ID_CLAIMS = List.of("sub", "user_id");
    // Claims to skip when building additionalClaims (internal JWT fields)
    private static final Set<String> SKIP_CLAIMS = Set.of("iss", "iat", "exp", "nbf", "jti", "aud");

    private final ObjectMapper objectMapper;

    public JwtUserIdExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String extractUserId(HttpServletRequest request) {
        Map<String, Object> claims = decodeClaims(request);
        if (claims == null) return null;

        for (String key : USER_ID_CLAIMS) {
            Object value = claims.get(key);
            if (value != null && !value.toString().trim().isEmpty()) {
                log.debug("Extracted user ID from JWT claim '{}': {}", key, value);
                return value.toString().trim();
            }
        }

        log.debug("No user ID claim found in JWT (looked for: {})", USER_ID_CLAIMS);
        return null;
    }

    @Override
    public Map<String, String> extractAdditionalClaims(HttpServletRequest request) {
        Map<String, Object> claims = decodeClaims(request);
        if (claims == null) return Map.of();

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            if (SKIP_CLAIMS.contains(key)) continue;
            if (USER_ID_CLAIMS.contains(key)) continue; // already used as user_id
            Object value = entry.getValue();
            if (value != null && !value.toString().trim().isEmpty()) {
                result.put(key, value.toString().trim());
                log.debug("Extracted JWT claim: {} = {}", key, value);
            }
        }
        return result;
    }

    /**
     * Decodes the JWT payload (middle part) from the Authorization header.
     * Returns null if no valid Bearer token is present.
     */
    private Map<String, Object> decodeClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.debug("No Bearer token in Authorization header");
            return null;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            log.warn("Invalid JWT format — expected 3 parts separated by '.'");
            return null;
        }

        try {
            // JWT payload is Base64url encoded (no padding)
            byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            return objectMapper.readValue(payloadBytes, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to decode JWT payload: {}", e.getMessage());
            return null;
        }
    }

    /** Adds Base64 padding if missing (JWT uses unpadded Base64url). */
    private String padBase64(String base64) {
        int mod = base64.length() % 4;
        if (mod == 2) return base64 + "==";
        if (mod == 3) return base64 + "=";
        return base64;
    }
}
