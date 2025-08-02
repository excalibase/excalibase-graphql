package io.github.excalibase.service;

import io.github.excalibase.model.RolePrivileges;
import java.util.Map;

/**
 * Interface for database-specific role privilege services.
 * Part of the Root + Filter approach for efficient role-based schema generation.
 */
public interface IRolePrivilegeService {
    
    /**
     * Gets comprehensive role privileges with caching.
     * This is much faster than SET ROLE approach for multiple roles.
     * 
     * @param roleName the database role name (null for default)
     * @return role privileges including table, column, and RLS privileges
     */
    RolePrivileges getRolePrivileges(String roleName);
    
    /**
     * Clear privilege cache (useful for testing or when roles change).
     */
    void clearCache();
    
    /**
     * Clear cache for specific role.
     * 
     * @param roleName the role to clear from cache
     */
    void clearCacheForRole(String roleName);
    
    /**
     * Get privilege cache statistics.
     * 
     * @return cache statistics for monitoring
     */
    Map<String, Object> getCacheStats();
    
    /**
     * Test role privileges programmatically.
     * Useful for debugging and admin interfaces.
     * 
     * @param roleName the role to test
     * @return test results including privilege summary
     */
    Map<String, Object> testRolePrivileges(String roleName);
} 