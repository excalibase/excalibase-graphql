package io.github.excalibase.postgres.service;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.DatabaseColumnConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.RlsPolicy;
import io.github.excalibase.model.RolePrivileges;
import io.github.excalibase.postgres.constant.PostgresErrorConstant;
import io.github.excalibase.service.IRolePrivilegeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL-specific service to query role privileges efficiently.
 * Part of the Root + Filter approach for role-based schema generation.
 */
@Service
@ExcalibaseService(serviceName = SupportedDatabaseConstant.POSTGRES)
public class RolePrivilegeService implements IRolePrivilegeService {
    private static final Logger log = LoggerFactory.getLogger(RolePrivilegeService.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final TTLCache<String, RolePrivileges> privilegeCache;
    
    public RolePrivilegeService(JdbcTemplate jdbcTemplate, AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.privilegeCache = new TTLCache<>(Duration.ofMinutes(appConfig.getCache().getRolePrivilegesTtlMinutes()));
    }

    /**
     * Gets comprehensive role privileges with caching.
     * This is much faster than SET ROLE approach for multiple roles.
     * ~20ms vs ~300ms for full schema reflection.
     */
    public RolePrivileges getRolePrivileges(String roleName) {
        if (roleName == null || roleName.trim().isEmpty()) {
            roleName = "default";
        }
        
        return privilegeCache.computeIfAbsent(roleName, role -> {
            log.debug("Querying {} privileges for {}: {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.ROLE, role);
            try {
                return queryRolePrivileges(role);
            } catch (Exception e) {
                log.warn("Failed to query {} {} for {}: {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.PRIVILEGES, PostgresErrorConstant.ROLE, e.getMessage());
                return createEmptyPrivileges(role);
            }
        });
    }

    /**
     * Queries PostgreSQL for all privileges of a specific role.
     */
    private RolePrivileges queryRolePrivileges(String roleName) {
        RolePrivileges privileges = new RolePrivileges(roleName);
        
        // Check if role is superuser
        boolean isSuperuser = checkIfSuperuser(roleName);
        if (isSuperuser) {
            log.debug("{} {} '{}' is {} - has all {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.ROLE, roleName, PostgresErrorConstant.SUPERUSER, PostgresErrorConstant.PRIVILEGES);
            return createSuperuserPrivileges(roleName);
        }
        
        // Query PostgreSQL-specific privilege sources
        queryTablePrivileges(roleName, privileges);
        queryColumnPrivileges(roleName, privileges);
        queryRlsPolicies(roleName, privileges);
        
        log.debug("Loaded {} {} for {} '{}': {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.PRIVILEGES, PostgresErrorConstant.ROLE, roleName, privileges);
        return privileges;
    }

    /**
     * Check if role is a PostgreSQL superuser.
     */
    private boolean checkIfSuperuser(String roleName) {
        if ("default".equals(roleName)) {
            return false;
        }
        
        try {
            String sql = "SELECT rolsuper FROM pg_roles WHERE rolname = ?";
            Boolean isSuperuser = jdbcTemplate.queryForObject(sql, Boolean.class, roleName);
            return Boolean.TRUE.equals(isSuperuser);
        } catch (Exception e) {
            log.debug("{} {} '{}' not found or error checking {} status: {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.ROLE, roleName, PostgresErrorConstant.SUPERUSER, e.getMessage());
            return false;
        }
    }

    /**
     * Query PostgreSQL table-level privileges from information_schema.
     */
    private void queryTablePrivileges(String roleName, RolePrivileges privileges) {
        String sql = """
            SELECT table_name, privilege_type
            FROM information_schema.table_privileges 
            WHERE grantee = ? AND table_schema = 'public'
            ORDER BY table_name, privilege_type
            """;
        
        try {
            jdbcTemplate.query(sql, rs -> {
                String tableName = rs.getString("table_name");
                String privilegeType = rs.getString("privilege_type");
                privileges.addTablePrivilege(tableName, privilegeType);
            }, roleName);
        } catch (Exception e) {
            log.warn("Failed to query {} table {} for {} '{}': {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.PRIVILEGES, PostgresErrorConstant.ROLE, roleName, e.getMessage());
        }
    }

    /**
     * Query PostgreSQL column-level privileges from information_schema.
     */
    private void queryColumnPrivileges(String roleName, RolePrivileges privileges) {
        String sql = """
            SELECT table_name, column_name, privilege_type
            FROM information_schema.column_privileges 
            WHERE grantee = ? AND table_schema = 'public'
            ORDER BY table_name, column_name, privilege_type
            """;
        
        try {
            jdbcTemplate.query(sql, rs -> {
                String tableName = rs.getString("table_name");
                String columnName = rs.getString("column_name");
                String privilegeType = rs.getString("privilege_type");
                privileges.addColumnPrivilege(privilegeType, tableName, columnName);
            }, roleName);
        } catch (Exception e) {
            log.warn("Failed to query {} column {} for {} '{}': {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.PRIVILEGES, PostgresErrorConstant.ROLE, roleName, e.getMessage());
        }
    }

    /**
     * Query PostgreSQL RLS policies from pg_policies.
     */
    private void queryRlsPolicies(String roleName, RolePrivileges privileges) {
        String sql = """
            SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
            FROM pg_policies 
            WHERE schemaname = 'public' 
              AND (? = ANY(roles) OR 'public' = ANY(roles))
            ORDER BY schemaname, tablename, policyname
            """;
        
        try {
            jdbcTemplate.query(sql, rs -> {
                String schemaName = rs.getString("schemaname");
                String tableName = rs.getString("tablename");
                String policyName = rs.getString("policyname");
                String permissiveStr = rs.getString("permissive");
                boolean permissive = "PERMISSIVE".equalsIgnoreCase(permissiveStr);
                
                // Parse PostgreSQL roles array
                String rolesStr = rs.getString("roles");
                List<String> roles = parsePostgresArray(rolesStr);
                
                String command = rs.getString("cmd");
                String usingExpression = rs.getString("qual");
                String withCheckExpression = rs.getString("with_check");
                
                RlsPolicy policy = new RlsPolicy(policyName, tableName, schemaName,
                        permissive, roles, command, usingExpression, withCheckExpression);
                privileges.addRlsPolicy(policy);
            }, roleName);
        } catch (Exception e) {
            log.warn("Failed to query PostgreSQL RLS policies for role '{}': {}", roleName, e.getMessage());
        }
    }

    /**
     * Parse PostgreSQL array string like "{role1,role2}" into List<String>.
     */
    private List<String> parsePostgresArray(String arrayStr) {
        if (arrayStr == null || arrayStr.trim().isEmpty()) {
            return List.of();
        }
        
        // Remove PostgreSQL array braces and split
        String cleaned = arrayStr.replaceAll("[{}]", "");
        if (cleaned.trim().isEmpty()) {
            return List.of();
        }
        
        return Arrays.stream(cleaned.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Create superuser privileges (access to everything in PostgreSQL).
     */
    private RolePrivileges createSuperuserPrivileges(String roleName) {
        // Superuser privileges are handled differently - they get "*" access
        Set<String> allTables = new HashSet<>();
        Map<String, Map<String, Set<String>>> columnPrivileges = new HashMap<>();
        
        return new RolePrivileges(roleName, true, allTables, allTables, allTables, allTables, 
                                 columnPrivileges, List.of());
    }

    /**
     * Create empty privileges for unknown/invalid PostgreSQL roles.
     */
    private RolePrivileges createEmptyPrivileges(String roleName) {
        return new RolePrivileges(roleName);
    }

    /**
     * Clear privilege cache (useful for testing or when PostgreSQL roles change).
     */
    public void clearCache() {
        privilegeCache.clear();
        log.info("{} {} privilege cache cleared", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.ROLE);
    }

    /**
     * Clear cache for specific PostgreSQL role.
     */
    public void clearCacheForRole(String roleName) {
        privilegeCache.remove(roleName);
        log.debug("Cleared {} privilege cache for {}: {}", PostgresErrorConstant.POSTGRESQL, PostgresErrorConstant.ROLE, roleName);
    }

    /**
     * Get PostgreSQL privilege cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", privilegeCache.size());
        stats.put("cacheInfo", PostgresErrorConstant.POSTGRESQL + " " + PostgresErrorConstant.ROLE + " privilege cache");
        return stats;
    }

    /**
     * Test PostgreSQL role privileges programmatically.
     * Useful for debugging and admin interfaces.
     */
    public Map<String, Object> testRolePrivileges(String roleName) {
        RolePrivileges privileges = getRolePrivileges(roleName);
        
        Map<String, Object> testResults = new HashMap<>();
        testResults.put(PostgresErrorConstant.DATABASE, PostgresErrorConstant.POSTGRESQL);
        testResults.put("roleName", roleName);
        testResults.put("isSuperuser", privileges.isSuperuser());
        testResults.put("selectableTables", privileges.getSelectableTables());
        testResults.put("insertableTables", privileges.getInsertableTables());
        testResults.put("updatableTables", privileges.getUpdatableTables());
        testResults.put("deletableTables", privileges.getDeletableTables());
        testResults.put("rlsPolicyCount", privileges.getRlsPolicies().size());
        testResults.put("isEmpty", privileges.isEmpty());
        
        return testResults;
    }
} 