package io.github.excalibase.extractor;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts user ID from HTTP headers.
 * Suitable for API Gateway scenarios where auth is handled upstream.
 *
 * Config:
 *   app.security.user-id-extractor-type: header
 *   app.security.user-id-header: X-User-Id   (default)
 *
 * Example request:
 *   X-User-Id: user-123
 *   X-Claim-tenant_id: acme-corp
 *   X-Claim-department_id: engineering
 *
 * These map to PostgreSQL session variables:
 *   request.user_id      = "user-123"
 *   request.jwt.tenant_id    = "acme-corp"
 *   request.jwt.department_id = "engineering"
 */
@Service
@ExcalibaseService(serviceName = "header")
public class HeaderUserIdExtractor implements IUserIdExtractor {
    private static final Logger log = LoggerFactory.getLogger(HeaderUserIdExtractor.class);
    private static final String CLAIM_HEADER_PREFIX = "x-claim-";

    private final AppConfig appConfig;

    public HeaderUserIdExtractor(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public String extractUserId(HttpServletRequest request) {
        String headerName = appConfig.getSecurity().getUserIdHeader();
        String userId = request.getHeader(headerName);
        if (userId != null && !userId.trim().isEmpty()) {
            log.debug("Extracted user ID from header '{}': {}", headerName, userId);
            return userId.trim();
        }
        log.debug("No user ID found in header '{}'", headerName);
        return null;
    }

    @Override
    public Map<String, String> extractAdditionalClaims(HttpServletRequest request) {
        Map<String, String> claims = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames == null) return claims;

        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name.toLowerCase().startsWith(CLAIM_HEADER_PREFIX)) {
                String claimName = name.substring(CLAIM_HEADER_PREFIX.length());
                String value = request.getHeader(name);
                if (value != null && !value.trim().isEmpty()) {
                    claims.put(claimName, value.trim());
                    log.debug("Extracted claim from header: {} = {}", claimName, value);
                }
            }
        }
        return claims;
    }
}
