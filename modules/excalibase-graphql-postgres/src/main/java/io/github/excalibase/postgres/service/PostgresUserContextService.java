package io.github.excalibase.postgres.service;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.service.IUserContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PostgreSQL implementation for setting user context via session variables.
 * Enables RLS policies using current_setting('request.user_id').
 */
@Service
@ExcalibaseService(serviceName = SupportedDatabaseConstant.POSTGRES)
public class PostgresUserContextService implements IUserContextService {
    private static final Logger log = LoggerFactory.getLogger(PostgresUserContextService.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgresUserContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Sets user context as PostgreSQL session variables.
     * These variables persist for the transaction and can be used in RLS policies.
     *
     * Example RLS policy:
     * CREATE POLICY user_policy ON orders
     * FOR SELECT TO PUBLIC
     * USING (user_id = current_setting('request.user_id'));
     *
     * @param userId the authenticated user ID
     * @param additionalClaims optional claims like tenant_id, department_id
     */
    @Override
    public void setUserContext(String userId, Map<String, String> additionalClaims) {
        if (userId == null || userId.trim().isEmpty()) {
            log.debug("No user ID provided, skipping user context setup");
            return;
        }

        try {
            // Set primary user_id
            setSessionVariable("request.user_id", userId);
            log.debug("Set user context: user_id={}", userId);

            // Set additional claims if provided
            if (additionalClaims != null && !additionalClaims.isEmpty()) {
                for (Map.Entry<String, String> entry : additionalClaims.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();

                    if (value != null && !value.isEmpty()) {
                        String variableName = "request.jwt." + sanitizeVariableName(key);
                        setSessionVariable(variableName, value);
                        log.debug("Set claim: {}={}", variableName, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to set user context: {}", e.getMessage());
            // Don't throw - let the query proceed, RLS will handle missing context
        }
    }

    /**
     * Clears all user context session variables.
     * Must be called after each request to prevent leakage in connection pools.
     */
    @Override
    public void clearUserContext() {
        try {
            // PostgreSQL RESET removes all LOCAL settings for this transaction
            jdbcTemplate.execute("RESET ALL");
            log.debug("Cleared user context");
        } catch (Exception e) {
            log.debug("Failed to clear user context (usually harmless): {}", e.getMessage());
        }
    }

    /**
     * Sets a single session variable using SET (session-scoped).
     * Persists for the entire connection so RLS policies can access it
     * across multiple autocommit statements within the same request.
     * The variable is explicitly cleared by clearUserContext() after each request.
     */
    private void setSessionVariable(String name, String value) {
        String sanitizedValue = sanitizeValue(value);
        String sql = String.format("SET %s = '%s'", name, sanitizedValue);
        jdbcTemplate.execute(sql);
    }

    /**
     * Sanitizes variable name - only allows alphanumeric and underscores.
     */
    private String sanitizeVariableName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9_.]", "_");
    }

    /**
     * Sanitizes value to prevent SQL injection - escapes single quotes.
     */
    private String sanitizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
