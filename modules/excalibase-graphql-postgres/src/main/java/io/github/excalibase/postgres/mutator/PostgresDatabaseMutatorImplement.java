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
package io.github.excalibase.postgres.mutator;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataMutationException;
import io.github.excalibase.postgres.constant.PostgresErrorConstant;
import io.github.excalibase.exception.NotFoundException;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.postgres.util.PostgresArrayParameterHandler;
import io.github.excalibase.postgres.util.PostgresSchemaHelper;
import io.github.excalibase.postgres.util.PostgresSqlBuilder;
import io.github.excalibase.postgres.util.PostgresTypeConverter;
import io.github.excalibase.schema.mutator.IDatabaseMutator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseMutatorImplement implements IDatabaseMutator {
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseMutatorImplement.class);
    private static final String TABLE_NOT_FOUND = "Table not found:";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;
    private final TransactionTemplate transactionTemplate;
    private IDatabaseSchemaReflector schemaReflector;
    
    // Utility classes (lazy initialization)
    private PostgresSchemaHelper schemaHelper;
    private PostgresSqlBuilder sqlBuilder;
    private PostgresTypeConverter typeConverter;
    private PostgresArrayParameterHandler arrayParameterHandler;

    public PostgresDatabaseMutatorImplement(JdbcTemplate jdbcTemplate, 
                                                     NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                                     ServiceLookup serviceLookup, 
                                                     AppConfig appConfig,
                                                     TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
        this.transactionTemplate = transactionTemplate;
        
        // Utility classes will be initialized lazily
    }

    private PostgresSchemaHelper getSchemaHelper() {
        if (schemaHelper == null) {
            schemaHelper = new PostgresSchemaHelper(getSchemaReflector());
        }
        return schemaHelper;
    }

    private PostgresTypeConverter getTypeConverter() {
        if (typeConverter == null) {
            typeConverter = new PostgresTypeConverter(getSchemaReflector());
        }
        return typeConverter;
    }

    private PostgresSqlBuilder getSqlBuilder() {
        if (sqlBuilder == null) {
            sqlBuilder = new PostgresSqlBuilder(getTypeConverter());
        }
        return sqlBuilder;
    }

    private PostgresArrayParameterHandler getArrayParameterHandler() {
        if (arrayParameterHandler == null) {
            arrayParameterHandler = new PostgresArrayParameterHandler(getTypeConverter());
        }
        return arrayParameterHandler;
    }

    @Override
    public DataFetcher<Map<String, Object>> buildCreateMutationResolver(String tableName) {
        return environment -> {
            TableInfo tableInfo = getTableInfo(tableName);

            Map<String, Object> input = environment.getArgument("input");
            if (input == null) {
                throw new IllegalArgumentException(String.format(PostgresErrorConstant.INPUT_REQUIRED_TEMPLATE, "create"));
            }

            Map<String, Object> nonNullInputs = input.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            getSchemaHelper().addRequiredTimestampFields(tableInfo, nonNullInputs);

            if (nonNullInputs.isEmpty()) {
                throw new IllegalArgumentException("No valid input fields provided for create operation");
            }

            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            String sql = getSqlBuilder().buildInsertSql(tableName, appConfig.getAllowedSchema(), nonNullInputs.keySet(), columnTypes);
            MapSqlParameterSource paramSource = createParameterSource(tableName, nonNullInputs);
            log.debug("Executing create SQL: {} with parameters: {}", sql, paramSource.getValues());

            try {
                Map<String, Object> result = namedParameterJdbcTemplate.queryForMap(sql, paramSource);
                // Apply the same composite type conversion as queries
                return getTypeConverter().convertPostgresTypesToGraphQLTypes(result, tableInfo);
            } catch (Exception e) {
                log.error("Error creating record in table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error creating record: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildUpdateMutationResolver(String tableName) {
        return environment -> {
            TableInfo tableInfo = getTableInfo(tableName);

            Map<String, Object> input = environment.getArgument("input");
            if (input == null) {
                throw new NotFoundException(String.format(PostgresErrorConstant.INPUT_REQUIRED_TEMPLATE, "update"));
            }
            
            // Find primary key column(s)
            List<String> primaryKeyColumns = getSchemaHelper().getPrimaryKeyColumns(tableName);

            Map<String, Object> primaryKeyValues = new HashMap<>();
            Map<String, Object> updateValues = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                if (value != null) {
                    if (primaryKeyColumns.contains(key)) {
                        primaryKeyValues.put(key, value);
                    } else {
                        updateValues.put(key, value);
                    }
                }
            }

            if (primaryKeyValues.isEmpty()) {
                throw new IllegalArgumentException("Primary key values must be provided for update operation");
            }
            
            if (updateValues.isEmpty()) {
                throw new IllegalArgumentException("No update values provided for update operation");
            }

            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            String sql = getSqlBuilder().buildUpdateSql(tableName, appConfig.getAllowedSchema(), updateValues.keySet(), primaryKeyValues.keySet(), columnTypes);

            Map<String, Object> allParams = new HashMap<>();
            allParams.putAll(primaryKeyValues);
            allParams.putAll(updateValues);
            
            MapSqlParameterSource paramSource = createParameterSource(tableName, allParams);
            
            log.debug("Executing update SQL: {} with parameters: {}", sql, paramSource.getValues());

            try {
                List<Map<String, Object>> results = namedParameterJdbcTemplate.queryForList(sql, paramSource);
                if (results.isEmpty()) {
                    throw new NotFoundException("No record found with the specified primary key");
                }
                // Apply the same composite type conversion as queries
                return getTypeConverter().convertPostgresTypesToGraphQLTypes(results.getFirst(), tableInfo);
            } catch (Exception e) {
                log.error("Error updating record in table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error updating record: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildDeleteMutationResolver(String tableName) {
        return environment -> {
            TableInfo tableInfo = getTableInfo(tableName);
            
            Map<String, Object> input = environment.getArgument("input");
            if (input == null) {
                throw new IllegalArgumentException(String.format(PostgresErrorConstant.INPUT_REQUIRED_TEMPLATE, "delete"));
            }
            
            // Find primary key column(s)
            List<String> primaryKeyColumns = new ArrayList<>();
            try {
                primaryKeyColumns = getSchemaHelper().getPrimaryKeyColumns(tableName);
            } catch (Exception e) {
                // Table might not have explicit primary keys
            }

            Map<String, Object> whereClause = new HashMap<>();
            
            if (!primaryKeyColumns.isEmpty()) {
                // Handle tables with explicit primary keys
                for (Map.Entry<String, Object> entry : input.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    
                    if (value != null && primaryKeyColumns.contains(key)) {
                        whereClause.put(key, value);
                    }
                }

                if (whereClause.isEmpty()) {
                    throw new IllegalArgumentException("Primary key values must be provided for delete operation");
                }
                
                // Validate that all primary key parts are provided
                for (String primaryKeyColumn : primaryKeyColumns) {
                    if (!whereClause.containsKey(primaryKeyColumn)) {
                        throw new IllegalArgumentException("Missing required primary key field: " + primaryKeyColumn);
                    }
                }
            } else {
                // Handle tables without explicit primary keys - use 'id' as fallback
                Object idValue = input.get("id");
                if (idValue == null) {
                    throw new IllegalArgumentException("ID is required for delete operation on table without primary key: " + tableName);
                }
                whereClause.put("id", idValue);
            }

            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            String sql = getSqlBuilder().buildDeleteSql(tableName, appConfig.getAllowedSchema(), whereClause.keySet(), columnTypes);
            
            MapSqlParameterSource paramSource = createParameterSource(tableName, whereClause);
            
            log.debug("Executing delete SQL: {} with parameters: {}", sql, paramSource.getValues());
            
            // Execute the delete and return the deleted record
            try {
                List<Map<String, Object>> results = namedParameterJdbcTemplate.queryForList(sql, paramSource);
                if (results.isEmpty()) {
                    throw new NotFoundException("No record found with the specified primary key");
                }
                return results.getFirst();
            } catch (NotFoundException e) {
                // Re-throw NotFoundException as-is
                throw e;
            } catch (Exception e) {
                log.error("Error deleting record from table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error deleting record: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> buildBulkCreateMutationResolver(String tableName) {
        return environment -> {
            // Get table info
            TableInfo tableInfo = getTableInfo(tableName);
            
            // Get input data as a list of objects
            List<Map<String, Object>> inputs = environment.getArgument("inputs");
            
            if (inputs == null || inputs.isEmpty()) {
                throw new IllegalArgumentException("No inputs provided for bulk create operation");
            }
            
            // Process inputs and add required timestamp fields
            List<Map<String, Object>> processedInputs = inputs.stream()
                .map(input -> {
                    Map<String, Object> processed = new HashMap<>(input);
                    getSchemaHelper().addRequiredTimestampFields(tableInfo, processed);
                    return processed;
                })
                .collect(Collectors.toList());
            
            // Get the union of all fields across all inputs
            Set<String> allFields = processedInputs.stream()
                .flatMap(input -> input.keySet().stream())
                .collect(Collectors.toSet());
            
            if (allFields.isEmpty()) {
                throw new IllegalArgumentException("No valid input fields provided for bulk create operation");
            }
            
            // Build bulk insert SQL
            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            String sql = getSqlBuilder().buildBulkInsertSql(tableName, appConfig.getAllowedSchema(), allFields, processedInputs.size(), columnTypes);
            MapSqlParameterSource paramSource = createBulkParameterSource(tableName, allFields, processedInputs);
            log.debug("Executing bulk create SQL: {}", sql);

            try {
                return namedParameterJdbcTemplate.queryForList(sql, paramSource);
            } catch (Exception e) {
                log.error("Error creating records in bulk for table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error creating records in bulk: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> buildCreateWithRelationshipsMutationResolver(String tableName) {
        return environment -> {
            return transactionTemplate.execute(status -> {
                try {
                    return createRecordWithRelationshipsTransactional(tableName, environment);
                } catch (Exception e) {
                    status.setRollbackOnly();
                    log.error("Transaction failed for create with relationships: {}", e.getMessage());
                    throw new DataMutationException("Transaction failed and was rolled back: " + e.getMessage(), e);
                }
            });
        };
    }

    private Map<String, Object> createRecordWithRelationshipsTransactional(String tableName, DataFetchingEnvironment environment) {
        // Get table info
        TableInfo tableInfo = getTableInfo(tableName);

        Map<String, Object> input = environment.getArgument("input");
        if (input == null) {
            throw new IllegalArgumentException(String.format(PostgresErrorConstant.INPUT_REQUIRED_TEMPLATE, "create with relationships"));
        }

        Map<String, Object> directFields = new HashMap<>();
        Map<String, Object> relationshipFields = new HashMap<>();

        List<ForeignKeyInfo> foreignKeys = tableInfo.getForeignKeys();
        Set<String> foreignKeyColumns = foreignKeys.stream()
            .map(ForeignKeyInfo::getColumnName)
            .collect(Collectors.toSet());

        Set<String> potentialRelationFields = input.keySet().stream()
            .filter(key -> key.endsWith("_connect") || key.endsWith("_create") || key.endsWith("_createMany"))
            .collect(Collectors.toSet());

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (potentialRelationFields.contains(key) || foreignKeyColumns.contains(key)) {
                relationshipFields.put(key, value);
            } else {
                directFields.put(key, value);
            }
        }

        Map<String, TableInfo> allTables = getSchemaHelper().getAllTables();
        Map<String, Object> foreignKeyValues = processRelationshipFields(relationshipFields, foreignKeys, allTables);

        directFields.putAll(foreignKeyValues);

        getSchemaHelper().addRequiredTimestampFields(tableInfo, directFields);

        Map<String, Object> createdRecord = createRecordInline(tableName, directFields);

        processCreateManyRelationships(relationshipFields, tableName, createdRecord, allTables);
        
        return createdRecord;
    }

    private Map<String, Object> processRelationshipFields(Map<String, Object> relationshipFields, 
                                                         List<ForeignKeyInfo> foreignKeys, 
                                                         Map<String, TableInfo> tables) {
        Map<String, Object> foreignKeyValues = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : relationshipFields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (key.endsWith("_connect") && value instanceof Map) {
                processConnectRelationship(key, (Map<String, Object>) value, foreignKeys, foreignKeyValues);
            } else if (key.endsWith("_create") && value instanceof Map) {
                processCreateRelationship(key, (Map<String, Object>) value, foreignKeys, foreignKeyValues);
            }
        }
        
        return foreignKeyValues;
    }

    private void processConnectRelationship(String key, Map<String, Object> connectObj, 
                                          List<ForeignKeyInfo> foreignKeys, 
                                          Map<String, Object> foreignKeyValues) {
        String relationName = key.substring(0, key.length() - "_connect".length());
        
        Optional<ForeignKeyInfo> fkInfo = foreignKeys.stream()
            .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(relationName))
            .findFirst();
        
        if (fkInfo.isPresent()) {
            Object referencedId = connectObj.get("id");
            if (referencedId != null) {
                foreignKeyValues.put(fkInfo.get().getColumnName(), referencedId);
            }
        }
    }

    private void processCreateRelationship(String key, Map<String, Object> createObj, 
                                         List<ForeignKeyInfo> foreignKeys, 
                                         Map<String, Object> foreignKeyValues) {
        String relationName = key.substring(0, key.length() - "_create".length());
        
        Optional<ForeignKeyInfo> fkInfo = foreignKeys.stream()
            .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(relationName))
            .findFirst();
        
        if (fkInfo.isPresent()) {
            Map<String, Object> createdRecord = createRecordInline(fkInfo.get().getReferencedTable(), createObj);
            Object createdId = createdRecord.get(fkInfo.get().getReferencedColumn());
            
            if (createdId != null) {
                foreignKeyValues.put(fkInfo.get().getColumnName(), createdId);
            }
        }
    }

    private void processCreateManyRelationships(Map<String, Object> relationshipFields, 
                                              String tableName, 
                                              Map<String, Object> createdRecord, 
                                              Map<String, TableInfo> tables) {
        for (Map.Entry<String, Object> entry : relationshipFields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (key.endsWith("_createMany") && value instanceof List) {
                String relationName = key.substring(0, key.length() - "_createMany".length());
                
                // Find reverse foreign key
                String reverseFK = getSchemaHelper().findReverseForeignKey(relationName, tableName, tables);
                
                if (reverseFK != null) {
                    String primaryKeyColumn = getSchemaHelper().getPrimaryKeyColumns(tableName).get(0);
                    Object primaryKeyValue = createdRecord.get(primaryKeyColumn);
                    
                    List<Map<String, Object>> childRecords = (List<Map<String, Object>>) value;
                    for (Map<String, Object> childRecord : childRecords) {
                        childRecord.put(reverseFK, primaryKeyValue);
                        createRecordInline(relationName, childRecord);
                    }
                }
            }
        }
    }

    private Map<String, Object> createRecordInline(String tableName, Map<String, Object> input) {
        // Filter out null values
        Map<String, Object> nonNullInputs = input.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        
        if (nonNullInputs.isEmpty()) {
            throw new IllegalArgumentException("No valid input fields provided for inline create operation on table: " + tableName);
        }
        
        // Build SQL and execute
        Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
        String sql = getSqlBuilder().buildInsertSql(tableName, appConfig.getAllowedSchema(), nonNullInputs.keySet(), columnTypes);
        MapSqlParameterSource paramSource = createParameterSource(tableName, nonNullInputs);
        
        log.debug("Executing transactional create SQL: {} with parameters: {}", sql, paramSource.getValues());
        
        try {
            return namedParameterJdbcTemplate.queryForMap(sql, paramSource);
        } catch (Exception e) {
            log.error("Error creating record inline in table {}: {}", tableName, e.getMessage());
            throw new DataMutationException("Error creating record in table " + tableName + ": " + e.getMessage(), e);
        }
    }

    // Helper methods

    private IDatabaseSchemaReflector getSchemaReflector() {
        if (schemaReflector != null) {
            return schemaReflector;
        }
        schemaReflector = serviceLookup.forBean(IDatabaseSchemaReflector.class, appConfig.getDatabaseType().getName());
        return schemaReflector;
    }

    private TableInfo getTableInfo(String tableName) {
        TableInfo tableInfo = getSchemaHelper().getTableInfo(tableName);
        if (tableInfo == null) {
            throw new NotFoundException(TABLE_NOT_FOUND + " " + tableName);
        }
        return tableInfo;
    }

    private MapSqlParameterSource createParameterSource(String tableName, Map<String, Object> fields) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
        
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String columnType = columnTypes.getOrDefault(key, "").toLowerCase();
            
            getArrayParameterHandler().addTypedParameter(paramSource, key, value, columnType);
        }
        
        return paramSource;
    }

    private MapSqlParameterSource createBulkParameterSource(String tableName, Set<String> allFields, List<Map<String, Object>> inputs) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
        
        for (int i = 0; i < inputs.size(); i++) {
            Map<String, Object> input = inputs.get(i);
            for (String field : allFields) {
                String paramName = field + "_" + i;
                Object value = input.getOrDefault(field, null);
                String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                
                getArrayParameterHandler().addTypedParameter(paramSource, paramName, value, columnType);
            }
        }
        
        return paramSource;
    }
}