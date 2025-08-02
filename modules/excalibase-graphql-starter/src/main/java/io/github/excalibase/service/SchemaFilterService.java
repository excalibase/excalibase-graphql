package io.github.excalibase.service;

import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.RolePrivileges;
import io.github.excalibase.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service to filter the full database schema based on role privileges.
 * Part of the Root + Filter approach for efficient role-based schema generation.
 * 
 * This service takes the complete schema and role privileges, then produces a filtered
 * schema that only includes tables and columns the role can access with appropriate
 * GraphQL operations (queries, mutations) based on PostgreSQL privileges.
 */
@Service
public class SchemaFilterService {
    private static final Logger log = LoggerFactory.getLogger(SchemaFilterService.class);

    /**
     * Filters the full schema based on role privileges.
     * Returns a new filtered schema containing only accessible tables and columns.
     * 
     * @param fullSchema Complete database schema from FullSchemaService
     * @param rolePrivileges Privileges for the specific role
     * @return Filtered schema with only accessible elements
     */
    public Map<String, TableInfo> filterSchemaForRole(
            Map<String, TableInfo> fullSchema, 
            RolePrivileges rolePrivileges) {
        
        if (rolePrivileges == null || rolePrivileges.isEmpty()) {
            log.debug("Empty privileges - returning empty schema");
            return Map.of();
        }
        
        if (rolePrivileges.isSuperuser()) {
            log.debug("Superuser role - returning full schema");
            return fullSchema;
        }
        
        Map<String, TableInfo> filteredSchema = new HashMap<>();
        
        for (Map.Entry<String, TableInfo> entry : fullSchema.entrySet()) {
            String tableName = entry.getKey();
            TableInfo originalTable = entry.getValue();
            
            if (!rolePrivileges.hasAnyPrivilegeOnTable(tableName)) {
                log.trace("Role '{}' has no privileges on table '{}' - skipping", 
                         rolePrivileges.getRoleName(), tableName);
                continue;
            }
            
            TableInfo filteredTable = filterTableForRole(originalTable, rolePrivileges);
            
            if (filteredTable != null && !filteredTable.getColumns().isEmpty()) {
                filteredSchema.put(tableName, filteredTable);
                log.trace("Filtered table '{}': {} columns accessible", 
                         tableName, filteredTable.getColumns().size());
            }
        }
        
        log.debug("Schema filtering for role '{}': {}/{} tables accessible", 
                 rolePrivileges.getRoleName(), filteredSchema.size(), fullSchema.size());
        
        return filteredSchema;
    }

    /**
     * Filters a single table based on role privileges.
     * Returns a new TableInfo with only accessible columns.
     */
    private TableInfo filterTableForRole(TableInfo originalTable, RolePrivileges rolePrivileges) {
        String tableName = originalTable.getName();
        
        Set<String> selectableColumns = rolePrivileges.getSelectableColumns(tableName);
        
        if (selectableColumns.isEmpty() && !rolePrivileges.canSelectFromTable(tableName)) {
            log.trace("Role '{}' cannot select from table '{}' - excluding", 
                     rolePrivileges.getRoleName(), tableName);
            return null;
        }
        
        List<ColumnInfo> filteredColumns = new ArrayList<>();
        boolean hasSuperuserAccess = rolePrivileges.isSuperuser();
        boolean hasTableSelectAccess = rolePrivileges.canSelectFromTable(tableName);
        
        for (ColumnInfo column : originalTable.getColumns()) {
            String columnName = column.getName();
            
            boolean canAccessColumn = hasSuperuserAccess ||
                                    hasTableSelectAccess ||
                                    selectableColumns.contains("*") ||
                                    selectableColumns.contains(columnName);
            
            if (canAccessColumn) {
                filteredColumns.add(column);
                log.trace("Column '{}' accessible for role '{}' in table '{}'", 
                         columnName, rolePrivileges.getRoleName(), tableName);
            } else {
                log.trace("Column '{}' not accessible for role '{}' in table '{}'", 
                         columnName, rolePrivileges.getRoleName(), tableName);
            }
        }
        
        if (filteredColumns.isEmpty()) {
            return null;
        }
        
        TableInfo filteredTable = new TableInfo();
        filteredTable.setName(originalTable.getName());
        filteredTable.setView(originalTable.isView());
        filteredTable.setColumns(filteredColumns);
        
        // Copy other table properties
        if (originalTable.getForeignKeys() != null) {
            filteredTable.setForeignKeys(originalTable.getForeignKeys());
        }
        
        return filteredTable;
    }

    /**
     * Get operation capabilities for a table based on role privileges.
     * This determines what GraphQL operations (queries, mutations) should be generated.
     */
    public TableOperationCapabilities getTableOperationCapabilities(
            String tableName, RolePrivileges rolePrivileges) {
        
        boolean canQuery = rolePrivileges.canSelectFromTable(tableName);
        boolean canCreate = rolePrivileges.canInsertIntoTable(tableName);
        boolean canUpdate = rolePrivileges.canUpdateTable(tableName);
        boolean canDelete = rolePrivileges.canDeleteFromTable(tableName);
        
        // Check for RLS policies that might affect operations
        boolean hasRlsPolicies = rolePrivileges.hasRlsPolicies(tableName);
        
        return new TableOperationCapabilities(tableName, canQuery, canCreate, canUpdate, canDelete, hasRlsPolicies);
    }

    /**
     * Get column-level operation capabilities for mutations.
     * This determines which columns can be included in INSERT/UPDATE mutations.
     */
    public ColumnOperationCapabilities getColumnOperationCapabilities(
            String tableName, String columnName, RolePrivileges rolePrivileges) {
        
        boolean canSelect = rolePrivileges.canSelectColumn(tableName, columnName);
        boolean canInsert = rolePrivileges.canInsertColumn(tableName, columnName);
        boolean canUpdate = rolePrivileges.canUpdateColumn(tableName, columnName);
        
        return new ColumnOperationCapabilities(columnName, canSelect, canInsert, canUpdate);
    }

    /**
     * Data class representing table-level operation capabilities.
     */
    public static class TableOperationCapabilities {
        private final String tableName;
        private final boolean canQuery;
        private final boolean canCreate;
        private final boolean canUpdate;
        private final boolean canDelete;
        private final boolean hasRlsPolicies;

        public TableOperationCapabilities(String tableName, boolean canQuery, boolean canCreate, 
                                        boolean canUpdate, boolean canDelete, boolean hasRlsPolicies) {
            this.tableName = tableName;
            this.canQuery = canQuery;
            this.canCreate = canCreate;
            this.canUpdate = canUpdate;
            this.canDelete = canDelete;
            this.hasRlsPolicies = hasRlsPolicies;
        }

        public String getTableName() { return tableName; }
        public boolean canQuery() { return canQuery; }
        public boolean canCreate() { return canCreate; }
        public boolean canUpdate() { return canUpdate; }
        public boolean canDelete() { return canDelete; }
        public boolean hasRlsPolicies() { return hasRlsPolicies; }
        
        public boolean hasAnyMutationCapability() {
            return canCreate || canUpdate || canDelete;
        }
        
        @Override
        public String toString() {
            return String.format("TableOps{table='%s', query=%s, create=%s, update=%s, delete=%s, rls=%s}",
                    tableName, canQuery, canCreate, canUpdate, canDelete, hasRlsPolicies);
        }
    }

    /**
     * Data class representing column-level operation capabilities.
     */
    public static class ColumnOperationCapabilities {
        private final String columnName;
        private final boolean canSelect;
        private final boolean canInsert;
        private final boolean canUpdate;

        public ColumnOperationCapabilities(String columnName, boolean canSelect, boolean canInsert, boolean canUpdate) {
            this.columnName = columnName;
            this.canSelect = canSelect;
            this.canInsert = canInsert;
            this.canUpdate = canUpdate;
        }

        public String getColumnName() { return columnName; }
        public boolean canSelect() { return canSelect; }
        public boolean canInsert() { return canInsert; }
        public boolean canUpdate() { return canUpdate; }
        
        public boolean hasAnyMutationCapability() {
            return canInsert || canUpdate;
        }

        @Override
        public String toString() {
            return String.format("ColumnOps{column='%s', select=%s, insert=%s, update=%s}",
                    columnName, canSelect, canInsert, canUpdate);
        }
    }

    /**
     * Get filtering statistics for debugging and monitoring.
     */
    public Map<String, Object> getFilteringStats(
            Map<String, TableInfo> fullSchema, 
            Map<String, TableInfo> filteredSchema, 
            RolePrivileges rolePrivileges) {
        
        int totalTables = fullSchema.size();
        int accessibleTables = filteredSchema.size();
        
        int totalColumns = fullSchema.values().stream()
                .mapToInt(table -> table.getColumns().size())
                .sum();
        
        int accessibleColumns = filteredSchema.values().stream()
                .mapToInt(table -> table.getColumns().size())
                .sum();
        
        return Map.of(
            "roleName", rolePrivileges.getRoleName(),
            "isSuperuser", rolePrivileges.isSuperuser(),
            "totalTables", totalTables,
            "accessibleTables", accessibleTables,
            "tableAccessPercentage", totalTables > 0 ? (accessibleTables * 100.0 / totalTables) : 0,
            "totalColumns", totalColumns,
            "accessibleColumns", accessibleColumns,
            "columnAccessPercentage", totalColumns > 0 ? (accessibleColumns * 100.0 / totalColumns) : 0,
            "hasRlsPolicies", !rolePrivileges.getRlsPolicies().isEmpty()
        );
    }
} 