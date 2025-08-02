package io.github.excalibase.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service for managing PostgreSQL database roles in GraphQL context.
 * Provides centralized SET ROLE / RESET ROLE functionality for RLS/CLS support.
 */
@Service
public class DatabaseRoleService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseRoleService.class);
    
    private final JdbcTemplate jdbcTemplate;

    public DatabaseRoleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes SET ROLE for PostgreSQL security (RLS/CLS).
     * This enables Row Level Security and Column Level Security policies
     * to be enforced by PostgreSQL for all subsequent database operations.
     * 
     * @param roleName the PostgreSQL role to set (must exist in database)
     */
    public void setRole(String roleName) {
        if (roleName == null || roleName.trim().isEmpty()) {
            log.debug("No role specified, skipping SET ROLE");
            return;
        }
        
        try {
            // Sanitize role name to prevent SQL injection
            String sanitizedRole = roleName.replace("'", "''");
            log.debug("Executing SET ROLE '{}' for PostgreSQL security", sanitizedRole);
            
            jdbcTemplate.execute("SET ROLE '" + sanitizedRole + "'");
            log.debug("Successfully set role to '{}'", sanitizedRole);
        } catch (Exception e) {
            log.warn("Failed to set database role '{}': {}", roleName, e.getMessage());
            // Continue execution - PostgreSQL will handle permission errors naturally
        }
    }

    /**
     * Resets the role to the default session role.
     * This is important for connection pooling to avoid role leakage between requests.
     */
    public void resetRole() {
        try {
            log.debug("Resetting database role to default");
            jdbcTemplate.execute("RESET ROLE");
            log.debug("Successfully reset role to default");
        } catch (Exception e) {
            log.debug("Failed to reset role (this is usually harmless): {}", e.getMessage());
        }
    }

    /**
     * Executes a database operation with a specific role, automatically resetting afterwards.
     * This is a utility method for operations that need role-specific access.
     * 
     * @param roleName the role to use for the operation
     * @param operation the operation to execute
     */
    public void executeWithRole(String roleName, Runnable operation) {
        try {
            setRole(roleName);
            operation.run();
        } finally {
            resetRole();
        }
    }
} 