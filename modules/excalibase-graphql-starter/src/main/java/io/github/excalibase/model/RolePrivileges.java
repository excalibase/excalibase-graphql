package io.github.excalibase.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents all PostgreSQL privileges for a specific role.
 * Used in the Root + Filter approach for efficient role-based schema generation.
 */
public class RolePrivileges {
    
    // Table-level privileges
    private final Set<String> selectableTables;
    private final Set<String> insertableTables;
    private final Set<String> updatableTables;
    private final Set<String> deletableTables;
    private final Set<String> truncatableTables;
    private final Set<String> referencableTables;
    
    // Column-level privileges: operation -> table -> Set<column_names>
    private final Map<String, Map<String, Set<String>>> columnPrivileges;
    
    // RLS policies that affect this role
    private final List<RlsPolicy> rlsPolicies;
    
    // Role information
    private final String roleName;
    private final boolean isSuperuser;

    public RolePrivileges(String roleName) {
        this.roleName = roleName;
        this.isSuperuser = false;
        this.selectableTables = new HashSet<>();
        this.insertableTables = new HashSet<>();
        this.updatableTables = new HashSet<>();
        this.deletableTables = new HashSet<>();
        this.truncatableTables = new HashSet<>();
        this.referencableTables = new HashSet<>();
        this.columnPrivileges = new HashMap<>();
        this.rlsPolicies = new ArrayList<>();
        
        // Initialize column privilege maps
        this.columnPrivileges.put("SELECT", new HashMap<>());
        this.columnPrivileges.put("INSERT", new HashMap<>());
        this.columnPrivileges.put("UPDATE", new HashMap<>());
        this.columnPrivileges.put("REFERENCES", new HashMap<>());
    }
    
    public RolePrivileges(String roleName, boolean isSuperuser,
                         Set<String> selectableTables, Set<String> insertableTables,
                         Set<String> updatableTables, Set<String> deletableTables,
                         Map<String, Map<String, Set<String>>> columnPrivileges,
                         List<RlsPolicy> rlsPolicies) {
        this.roleName = roleName;
        this.isSuperuser = isSuperuser;
        this.selectableTables = selectableTables != null ? selectableTables : new HashSet<>();
        this.insertableTables = insertableTables != null ? insertableTables : new HashSet<>();
        this.updatableTables = updatableTables != null ? updatableTables : new HashSet<>();
        this.deletableTables = deletableTables != null ? deletableTables : new HashSet<>();
        this.truncatableTables = new HashSet<>();
        this.referencableTables = new HashSet<>();
        this.columnPrivileges = columnPrivileges != null ? columnPrivileges : new HashMap<>();
        this.rlsPolicies = rlsPolicies != null ? rlsPolicies : new ArrayList<>();
    }

    // =====================================================
    // Table-level privilege checks
    // =====================================================
    
    public boolean canSelectFromTable(String tableName) {
        return isSuperuser || selectableTables.contains(tableName);
    }
    
    public boolean canInsertIntoTable(String tableName) {
        return isSuperuser || insertableTables.contains(tableName);
    }
    
    public boolean canUpdateTable(String tableName) {
        return isSuperuser || updatableTables.contains(tableName);
    }
    
    public boolean canDeleteFromTable(String tableName) {
        return isSuperuser || deletableTables.contains(tableName);
    }

    // =====================================================
    // Column-level privilege checks
    // =====================================================
    
    public Set<String> getSelectableColumns(String tableName) {
        if (isSuperuser) {
            return Set.of("*"); // Superuser can access all columns
        }
        return columnPrivileges.getOrDefault("SELECT", Map.of())
                              .getOrDefault(tableName, Set.of());
    }
    
    public Set<String> getInsertableColumns(String tableName) {
        if (isSuperuser) {
            return Set.of("*");
        }
        return columnPrivileges.getOrDefault("INSERT", Map.of())
                              .getOrDefault(tableName, Set.of());
    }
    
    public Set<String> getUpdatableColumns(String tableName) {
        if (isSuperuser) {
            return Set.of("*");
        }
        return columnPrivileges.getOrDefault("UPDATE", Map.of())
                              .getOrDefault(tableName, Set.of());
    }
    
    public boolean canSelectColumn(String tableName, String columnName) {
        if (isSuperuser) return true;
        Set<String> columns = getSelectableColumns(tableName);
        return columns.contains("*") || columns.contains(columnName);
    }
    
    public boolean canInsertColumn(String tableName, String columnName) {
        if (isSuperuser) return true;
        Set<String> columns = getInsertableColumns(tableName);
        return columns.contains("*") || columns.contains(columnName);
    }
    
    public boolean canUpdateColumn(String tableName, String columnName) {
        if (isSuperuser) return true;
        Set<String> columns = getUpdatableColumns(tableName);
        return columns.contains("*") || columns.contains(columnName);
    }

    // =====================================================
    // RLS Policy access
    // =====================================================
    
    public List<RlsPolicy> getRlsPoliciesForTable(String tableName) {
        return rlsPolicies.stream()
                .filter(policy -> policy.getTableName().equals(tableName))
                .toList();
    }
    
    public boolean hasRlsPolicies(String tableName) {
        return rlsPolicies.stream()
                .anyMatch(policy -> policy.getTableName().equals(tableName));
    }

    // =====================================================
    // Utility methods
    // =====================================================
    
    public boolean hasAnyPrivilegeOnTable(String tableName) {
        // Check table-level privileges
        if (canSelectFromTable(tableName) || 
            canInsertIntoTable(tableName) || 
            canUpdateTable(tableName) || 
            canDeleteFromTable(tableName)) {
            return true;
        }
        
        // Check column-level privileges
        return hasAnyColumnPrivilegeOnTable(tableName);
    }
    
    /**
     * Check if role has any column-level privileges on a table.
     */
    private boolean hasAnyColumnPrivilegeOnTable(String tableName) {
        return !getSelectableColumns(tableName).isEmpty() ||
               !getInsertableColumns(tableName).isEmpty() ||
               !getUpdatableColumns(tableName).isEmpty();
    }
    
    public boolean isEmpty() {
        return selectableTables.isEmpty() && 
               insertableTables.isEmpty() && 
               updatableTables.isEmpty() && 
               deletableTables.isEmpty() &&
               columnPrivileges.values().stream().allMatch(Map::isEmpty);
    }

    // =====================================================
    // Builder methods for adding privileges
    // =====================================================
    
    public void addTablePrivilege(String tableName, String privilege) {
        switch (privilege.toUpperCase()) {
            case "SELECT" -> selectableTables.add(tableName);
            case "INSERT" -> insertableTables.add(tableName);
            case "UPDATE" -> updatableTables.add(tableName);
            case "DELETE" -> deletableTables.add(tableName);
            case "TRUNCATE" -> truncatableTables.add(tableName);
            case "REFERENCES" -> referencableTables.add(tableName);
        }
    }
    
    public void addColumnPrivilege(String operation, String tableName, String columnName) {
        columnPrivileges
            .computeIfAbsent(operation.toUpperCase(), k -> new HashMap<>())
            .computeIfAbsent(tableName, k -> new HashSet<>())
            .add(columnName);
    }
    
    public void addRlsPolicy(RlsPolicy policy) {
        this.rlsPolicies.add(policy);
    }

    // =====================================================
    // Getters
    // =====================================================
    
    public String getRoleName() {
        return roleName;
    }
    
    public boolean isSuperuser() {
        return isSuperuser;
    }
    
    public Set<String> getSelectableTables() {
        return Set.copyOf(selectableTables);
    }
    
    public Set<String> getInsertableTables() {
        return Set.copyOf(insertableTables);
    }
    
    public Set<String> getUpdatableTables() {
        return Set.copyOf(updatableTables);
    }
    
    public Set<String> getDeletableTables() {
        return Set.copyOf(deletableTables);
    }
    
    public List<RlsPolicy> getRlsPolicies() {
        return List.copyOf(rlsPolicies);
    }

    @Override
    public String toString() {
        return String.format("RolePrivileges{role='%s', superuser=%s, tables={select=%d, insert=%d, update=%d, delete=%d}, columns=%d, policies=%d}",
                roleName, isSuperuser, 
                selectableTables.size(), insertableTables.size(), updatableTables.size(), deletableTables.size(),
                columnPrivileges.values().stream().mapToInt(m -> m.values().stream().mapToInt(Set::size).sum()).sum(),
                rlsPolicies.size());
    }
} 