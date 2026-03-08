package io.github.excalibase.extractor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Interface for extracting user ID and claims from HTTP requests.
 * Allows pluggable strategies: headers, JWT tokens, OAuth2, etc.
 *
 * Implementations should be annotated with @ExcalibaseService(serviceName = "type")
 * where type matches the configured extractor type (e.g., "header", "jwt").
 */
public interface IUserIdExtractor {

    /**
     * Extract user ID from the HTTP request.
     *
     * @param request the HTTP request
     * @return user ID string, or null if not found/not authenticated
     */
    String extractUserId(HttpServletRequest request);

    /**
     * Extract additional claims from the request.
     * Used for multi-tenant isolation or fine-grained RLS policies.
     *
     * @param request the HTTP request
     * @return map of claim name to value, never null
     */
    Map<String, String> extractAdditionalClaims(HttpServletRequest request);
}
