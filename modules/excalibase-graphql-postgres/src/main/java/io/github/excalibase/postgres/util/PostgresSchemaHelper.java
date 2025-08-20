/*
 * Copyright 2025 Excalibase Team and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.excalibase.postgres.util;

import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.exception.DataFetcherException;
import io.github.excalibase.exception.NotFoundException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class PostgresSchemaHelper {
    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaHelper.class);
    
    private final IDatabaseSchemaReflector schemaReflector;

    public PostgresSchemaHelper(IDatabaseSchemaReflector schemaReflector) {
        this.schemaReflector = schemaReflector;
    }

    public Map<String, String> getColumnTypes(String tableName) {
        Map<String, String> columnTypes = new HashMap<>();
        
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            for (ColumnInfo column : tableInfo.getColumns()) {
                columnTypes.put(column.getName(), column.getType().toLowerCase());
            }
        }
        
        return columnTypes;
    }

    public String getColumnType(String tableName, String columnName) {
        return getColumnTypes(tableName).getOrDefault(columnName, "");
    }

    public List<String> getPrimaryKeyColumns(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo == null) {
            throw new DataFetcherException("Table not found: " + tableName);
        }
        
        List<String> primaryKeys = tableInfo.getColumns().stream()
            .filter(ColumnInfo::isPrimaryKey)
            .map(ColumnInfo::getName)
            .toList();
            
        if (primaryKeys.isEmpty()) {
            throw new NotFoundException("No primary key found for table: " + tableName);
        }
        
        return primaryKeys;
    }

    @Deprecated
    public String getPrimaryKeyColumn(String tableName) {
        List<String> primaryKeys = getPrimaryKeyColumns(tableName);
        return primaryKeys.get(0); // Return first primary key for backward compatibility
    }

    public TableInfo getTableInfo(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        return tables.get(tableName);
    }

    public Map<String, TableInfo> getAllTables() {
        return schemaReflector.reflectSchema();
    }

    public void addRequiredTimestampFields(TableInfo tableInfo, Map<String, Object> fields) {
        Set<String> requiredTimestampFields = tableInfo.getColumns().stream()
            .filter(col -> !col.isNullable() && 
                          (col.getType().toLowerCase().contains(ColumnTypeConstant.TIMESTAMP) || 
                           col.getType().toLowerCase().contains(ColumnTypeConstant.DATE)))
            .map(ColumnInfo::getName)
            .collect(Collectors.toSet());
        
        for (String field : requiredTimestampFields) {
            if (!fields.containsKey(field)) {
                fields.put(field, new Timestamp(System.currentTimeMillis()));
            }
        }
    }

    public String findReverseForeignKey(String relationTableName, String parentTableName, Map<String, TableInfo> tables) {
        TableInfo relationTableInfo = tables.get(relationTableName);
        
        if (relationTableInfo != null) {
            for (ForeignKeyInfo fk : relationTableInfo.getForeignKeys()) {
                if (fk.getReferencedTable().equalsIgnoreCase(parentTableName)) {
                    return fk.getColumnName();
                }
            }
        }
        
        return null;
    }

    public Optional<ForeignKeyInfo> findForeignKeyInfo(String tableName, String relationName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getForeignKeys().stream()
                .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(relationName))
                .findFirst();
        }
        
        return Optional.empty();
    }

    public boolean tableExists(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        return tables.containsKey(tableName);
    }

    public boolean columnExists(String tableName, String columnName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .anyMatch(col -> col.getName().equalsIgnoreCase(columnName));
        }
        
        return false;
    }

    public List<String> getAvailableColumns(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .map(ColumnInfo::getName)
                .collect(Collectors.toList());
        }
        
        return List.of();
    }

    public Set<String> getAvailableColumnsAsSet(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .map(ColumnInfo::getName)
                .collect(Collectors.toSet());
        }
        
        return Set.of();
    }

    public List<ForeignKeyInfo> getForeignKeys(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getForeignKeys();
        }
        
        return List.of();
    }

    public Set<String> getForeignKeyColumns(String tableName) {
        List<ForeignKeyInfo> foreignKeys = getForeignKeys(tableName);
        return foreignKeys.stream()
            .map(ForeignKeyInfo::getColumnName)
            .collect(Collectors.toSet());
    }

    public boolean isNullableColumn(String tableName, String columnName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            Optional<ColumnInfo> column = tableInfo.getColumns().stream()
                .filter(col -> col.getName().equalsIgnoreCase(columnName))
                .findFirst();
            
            if (column.isPresent()) {
                return column.get().isNullable();
            }
        }
        
        return true; // Default to nullable if column not found
    }

    public boolean isPrimaryKeyColumn(String tableName, String columnName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .anyMatch(col -> col.getName().equalsIgnoreCase(columnName) && col.isPrimaryKey());
        }
        
        return false;
    }

    public ColumnInfo getColumnInfo(String tableName, String columnName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .filter(col -> col.getName().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
        }
        
        return null;
    }

    public boolean hasPrimaryKey(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .anyMatch(ColumnInfo::isPrimaryKey);
        }
        
        return false;
    }

    public boolean hasCompositeKey(String tableName) {
        List<String> primaryKeys = getPrimaryKeyColumns(tableName);
        return primaryKeys.size() > 1;
    }

    public Map<String, ColumnInfo> getColumnsMap(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .collect(Collectors.toMap(ColumnInfo::getName, col -> col));
        }
        
        return Map.of();
    }

    public List<String> getRequiredFields(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .filter(col -> !col.isNullable())
                .map(ColumnInfo::getName)
                .collect(Collectors.toList());
        }
        
        return List.of();
    }

    public List<String> getOptionalFields(String tableName) {
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            return tableInfo.getColumns().stream()
                .filter(ColumnInfo::isNullable)
                .map(ColumnInfo::getName)
                .collect(Collectors.toList());
        }
        
        return List.of();
    }

    public void validateTableExists(String tableName) {
        if (!tableExists(tableName)) {
            throw new DataFetcherException("Table not found: " + tableName);
        }
    }

    public void validateColumnExists(String tableName, String columnName) {
        validateTableExists(tableName);
        if (!columnExists(tableName, columnName)) {
            throw new DataFetcherException("Column '" + columnName + "' not found in table: " + tableName);
        }
    }

    public void validateColumnsExist(String tableName, Set<String> columnNames) {
        validateTableExists(tableName);
        Set<String> availableColumns = getAvailableColumnsAsSet(tableName);
        
        for (String columnName : columnNames) {
            if (!availableColumns.contains(columnName)) {
                throw new DataFetcherException("Column '" + columnName + "' not found in table: " + tableName);
            }
        }
    }

    public boolean isReadOnlyTable(String tableName) {
        // Add logic here to determine if a table is read-only based on your business rules
        // For example, checking table name patterns, metadata, etc.
        return tableName.toLowerCase().startsWith("readonly_") || 
               tableName.toLowerCase().endsWith("_view");
    }

    public Map<String, String> getTableStatistics(String tableName) {
        Map<String, String> stats = new HashMap<>();
        TableInfo tableInfo = getTableInfo(tableName);
        
        if (tableInfo != null) {
            stats.put("columnCount", String.valueOf(tableInfo.getColumns().size()));
            stats.put("foreignKeyCount", String.valueOf(tableInfo.getForeignKeys().size()));
            stats.put("primaryKeyCount", String.valueOf(getPrimaryKeyColumns(tableName).size()));
            stats.put("requiredFieldCount", String.valueOf(getRequiredFields(tableName).size()));
            stats.put("optionalFieldCount", String.valueOf(getOptionalFields(tableName).size()));
        }
        
        return stats;
    }
}