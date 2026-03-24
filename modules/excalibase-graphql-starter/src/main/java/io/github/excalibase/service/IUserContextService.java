package io.github.excalibase.service;

import java.util.Map;

/**
 * Interface for setting user context in database session for RLS (Row Level Security) policies.
 *
 * This enables dynamic RLS policies:
 * CREATE POLICY user_policy ON orders
 * FOR SELECT TO PUBLIC
 * USING (user_id = current_setting('request.user_id'));
 *
 * Usage:
 * 1. Extract user ID from JWT token OR request header
 * 2. Call setUserContext(userId, claims) before executing queries
 * 3. PostgreSQL RLS policies automatically filter rows based on user context
 * 4. Call clearUserContext() after request to prevent leakage in connection pools
 */
public interface IUserContextService {

    /**
     * Sets user context for the current database session.
     * This sets PostgreSQL session variables that RLS policies can reference.
     *
     * @param userId the authenticated user ID (required)
     * @param additionalClaims optional additional claims (e.g., tenant_id, department_id)
     */
    void setUserContext(String userId, Map<String, String> additionalClaims);

    /**
     * Clears user context after request.
     * Important for connection pooling to prevent user context leakage between requests.
     */
    void clearUserContext();
}
