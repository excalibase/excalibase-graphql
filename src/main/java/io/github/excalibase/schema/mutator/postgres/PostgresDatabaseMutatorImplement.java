package io.github.excalibase.schema.mutator.postgres;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.constant.SQLSyntax;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataMutationException;
import io.github.excalibase.exception.DataFetcherException;
import io.github.excalibase.exception.NotFoundException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.mutator.IDatabaseMutator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    }

    @Override
    public DataFetcher<Map<String, Object>> createCreateMutationResolver(String tableName) {
        return environment -> {
            TableInfo tableInfo = getTableInfo(tableName);

            Map<String, Object> input = environment.getArgument("input");
            if (input == null) {
                throw new IllegalArgumentException("Input data is required for create operation");
            }

            Map<String, Object> nonNullInputs = input.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            addRequiredTimestampFields(tableInfo, nonNullInputs);

            if (nonNullInputs.isEmpty()) {
                throw new IllegalArgumentException("No valid input fields provided for create operation");
            }

            String sql = buildInsertSql(tableName, nonNullInputs.keySet());
            MapSqlParameterSource paramSource = createParameterSource(tableName, nonNullInputs);
            log.debug("Executing create SQL: {} with parameters: {}", sql, paramSource.getValues());

            try {
                return namedParameterJdbcTemplate.queryForMap(sql, paramSource);
            } catch (Exception e) {
                log.error("Error creating record in table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error creating record: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> createUpdateMutationResolver(String tableName) {
        return environment -> {
            TableInfo tableInfo = getTableInfo(tableName);

            Map<String, Object> input = environment.getArgument("input");
            if (input == null) {
                throw new NotFoundException("Input data is required for update operation");
            }
            
            // Find primary key column(s)
            List<String> primaryKeyColumns = tableInfo.getColumns().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .map(ColumnInfo::getName)
                .toList();
            
            if (primaryKeyColumns.isEmpty()) {
                throw new NotFoundException("No primary key found for table: " + tableName);
            }

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

            String sql = buildUpdateSql(tableName, updateValues.keySet(), primaryKeyValues.keySet());

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
                return results.getFirst();
            } catch (Exception e) {
                log.error("Error updating record in table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error updating record: " + e.getMessage(), e);
            }
        };
    }

    private TableInfo getTableInfo(String tableName) {
        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);

        if (tableInfo == null) {
            throw new NotFoundException(TABLE_NOT_FOUND + " " + tableName);
        }
        return tableInfo;
    }

    @Override
    public DataFetcher<Boolean> createDeleteMutationResolver(String tableName) {
        return environment -> {
            String id = environment.getArgument("id");
            if (id == null) {
                throw new IllegalArgumentException("ID is required for delete operation");
            }
            
            // Get table info to find primary key column
            Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);
            
            if (tableInfo == null) {
                throw new NotFoundException(TABLE_NOT_FOUND + " " + tableName);
            }
            
            // Find primary key column
            String primaryKeyColumn = tableInfo.getColumns().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .findFirst()
                .map(ColumnInfo::getName)
                .orElseThrow(() -> new DataFetcherException("No primary key found for table: " + tableName));
            
            // Build the SQL DELETE statement
            String sql = SQLSyntax.DELETE_WITH_SPACE + getQualifiedTableName(tableName) + SQLSyntax.WHERE_WITH_SPACE +
                        quoteIdentifier(primaryKeyColumn) + " = :id";
            
            // Prepare parameters with type conversion
            MapSqlParameterSource paramSource = new MapSqlParameterSource();
            addTypedParameter(paramSource, "id", id, getColumnType(tableName, primaryKeyColumn));
            
            log.debug("Executing delete SQL: {} with parameters: {}", sql, paramSource.getValues());
            
            // Execute the delete
            try {
                int rowsAffected = namedParameterJdbcTemplate.update(sql, paramSource);
                return rowsAffected > 0;
            } catch (Exception e) {
                log.error("Error deleting record from table {}: {}", tableName, e.getMessage());
                throw new DataMutationException("Error deleting record: " + e.getMessage(), e);
            }
        };
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> createBulkCreateMutationResolver(String tableName) {
        return environment -> {
            // Get table info
            Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);
            
            if (tableInfo == null) {
                throw new NotFoundException(TABLE_NOT_FOUND + " " + tableName);
            }
            
            // Get input data as a list of objects
            List<Map<String, Object>> inputs = environment.getArgument("inputs");
            
            if (inputs == null || inputs.isEmpty()) {
                throw new IllegalArgumentException("No inputs provided for bulk create operation");
            }
            
            // Process inputs and add required timestamp fields
            List<Map<String, Object>> processedInputs = inputs.stream()
                .map(input -> {
                    Map<String, Object> processed = new HashMap<>(input);
                    addRequiredTimestampFields(tableInfo, processed);
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
            String sql = buildBulkInsertSql(tableName, allFields, processedInputs.size());
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
    public DataFetcher<Map<String, Object>> createCreateWithRelationshipsMutationResolver(String tableName) {
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

    /**
     * Creates a record with relationships within a transaction.
     */
    private Map<String, Object> createRecordWithRelationshipsTransactional(String tableName, DataFetchingEnvironment environment) {
        // Get table info
        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo == null) {
            throw new NotFoundException(TABLE_NOT_FOUND + " " + tableName);
        }

        Map<String, Object> input = environment.getArgument("input");
        if (input == null) {
            throw new IllegalArgumentException("Input data is required for create with relationships operation");
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

        Map<String, Object> foreignKeyValues = processRelationshipFields(relationshipFields, foreignKeys, tables);

        directFields.putAll(foreignKeyValues);

        addRequiredTimestampFields(tableInfo, directFields);

        Map<String, Object> createdRecord = createRecordInline(tableName, directFields);

        processCreateManyRelationships(relationshipFields, tableName, createdRecord, tables);
        
        return createdRecord;
    }

    /**
     * Processes relationship fields to extract foreign key values.
     */
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

    /**
     * Processes a "_connect" relationship to link to an existing record.
     */
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

    /**
     * Processes a "_create" relationship to create a new related record.
     */
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

    /**
     * Processes "_createMany" relationships to create multiple child records.
     */
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
                String reverseFK = findReverseForeignKey(relationName, tableName, tables);
                
                if (reverseFK != null) {
                    Object primaryKeyValue = createdRecord.get(getPrimaryKeyColumn(tableName));
                    
                    List<Map<String, Object>> childRecords = (List<Map<String, Object>>) value;
                    for (Map<String, Object> childRecord : childRecords) {
                        childRecord.put(reverseFK, primaryKeyValue);
                        createRecordInline(relationName, childRecord);
                    }
                }
            }
        }
    }

    /**
     * Creates a record inline within the same transaction.
     */
    private Map<String, Object> createRecordInline(String tableName, Map<String, Object> input) {
        // Filter out null values
        Map<String, Object> nonNullInputs = input.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        
        if (nonNullInputs.isEmpty()) {
            throw new IllegalArgumentException("No valid input fields provided for inline create operation on table: " + tableName);
        }
        
        // Build SQL and execute
        String sql = buildInsertSql(tableName, nonNullInputs.keySet());
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

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String getQualifiedTableName(String tableName) {
        return appConfig.getAllowedSchema() + "." + quoteIdentifier(tableName);
    }

    private void addRequiredTimestampFields(TableInfo tableInfo, Map<String, Object> fields) {
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

    private String buildInsertSql(String tableName, Set<String> fieldNames) {
        return "INSERT INTO " + getQualifiedTableName(tableName) + " (" +
               fieldNames.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) +
               ") VALUES (" +
               fieldNames.stream().map(field -> ":" + field).collect(Collectors.joining(", ")) +
               ") RETURNING *";
    }

    private String buildUpdateSql(String tableName, Set<String> updateFields, Set<String> whereFields) {
        return "UPDATE " + getQualifiedTableName(tableName) + " SET " +
               updateFields.stream().map(field -> quoteIdentifier(field) + " = :" + field).collect(Collectors.joining(", ")) +
               " WHERE " +
               whereFields.stream().map(field -> quoteIdentifier(field) + " = :" + field).collect(Collectors.joining(" AND ")) +
               " RETURNING *";
    }

    private String buildBulkInsertSql(String tableName, Set<String> allFields, int recordCount) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
            .append(getQualifiedTableName(tableName))
            .append(" (")
            .append(allFields.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
            .append(") VALUES ");
        
        for (int i = 0; i < recordCount; i++) {
            if (i > 0) sql.append(", ");
            final int index = i; // Make it final for use in stream
            sql.append("(")
               .append(allFields.stream().map(field -> ":" + field + "_" + index).collect(Collectors.joining(", ")))
               .append(")");
        }
        
        sql.append(" RETURNING *");
        return sql.toString();
    }

    private MapSqlParameterSource createParameterSource(String tableName, Map<String, Object> fields) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        Map<String, String> columnTypes = getColumnTypes(tableName);
        
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String columnType = columnTypes.getOrDefault(key, "").toLowerCase();
            
            addTypedParameter(paramSource, key, value, columnType);
        }
        
        return paramSource;
    }

    private MapSqlParameterSource createBulkParameterSource(String tableName, Set<String> allFields, List<Map<String, Object>> inputs) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        Map<String, String> columnTypes = getColumnTypes(tableName);
        
        for (int i = 0; i < inputs.size(); i++) {
            Map<String, Object> input = inputs.get(i);
            for (String field : allFields) {
                String paramName = field + "_" + i;
                Object value = input.getOrDefault(field, null);
                String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                
                addTypedParameter(paramSource, paramName, value, columnType);
            }
        }
        
        return paramSource;
    }

    private void addTypedParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
        if (value == null || value.toString().isEmpty()) {
            paramSource.addValue(paramName, null);
            return;
        }

        String valueStr = value.toString();
        
        try {
            if (columnType.contains(ColumnTypeConstant.UUID)) {
                paramSource.addValue(paramName, UUID.fromString(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.INT) && !columnType.contains(ColumnTypeConstant.BIGINT)) {
                paramSource.addValue(paramName, Integer.parseInt(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.BIGINT)) {
                paramSource.addValue(paramName, Long.parseLong(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.DECIMAL) || columnType.contains(ColumnTypeConstant.NUMERIC) || 
                      columnType.contains(ColumnTypeConstant.DOUBLE) || columnType.contains(ColumnTypeConstant.FLOAT)) {
                paramSource.addValue(paramName, Double.parseDouble(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.BOOL)) {
                paramSource.addValue(paramName, Boolean.parseBoolean(valueStr));
            } else if ((columnType.contains(ColumnTypeConstant.TIMESTAMP) || columnType.contains(ColumnTypeConstant.DATE)) && value instanceof String) {
                try {
                    Timestamp timestamp = Timestamp.valueOf(((String) value).replace('T', ' ').replace('Z', ' ').trim());
                    paramSource.addValue(paramName, timestamp);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid timestamp format for {}, using as string: {}", paramName, value);
                    paramSource.addValue(paramName, valueStr);
                }
            } else {
                paramSource.addValue(paramName, value);
            }
        } catch (Exception e) {
            log.warn("Error converting value for parameter {}: {}, using as string", paramName, e.getMessage());
            paramSource.addValue(paramName, valueStr);
        }
    }

    private Map<String, String> getColumnTypes(String tableName) {
        Map<String, String> columnTypes = new HashMap<>();
        
        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        if (tableInfo != null) {
            for (ColumnInfo column : tableInfo.getColumns()) {
                columnTypes.put(column.getName(), column.getType().toLowerCase());
            }
        }
        
        return columnTypes;
    }

    private String getColumnType(String tableName, String columnName) {
        return getColumnTypes(tableName).getOrDefault(columnName, "");
    }

    private String getPrimaryKeyColumn(String tableName) {
        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);
        
        return tableInfo.getColumns().stream()
            .filter(ColumnInfo::isPrimaryKey)
            .findFirst()
            .map(ColumnInfo::getName)
            .orElseThrow(() -> new DataFetcherException("No primary key found for table: " + tableName));
    }

    private String findReverseForeignKey(String relationTableName, String parentTableName, Map<String, TableInfo> tables) {
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
} 