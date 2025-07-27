package io.github.excalibase.postgres.mutator;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.constant.PostgresTypeOperator;
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
        Map<String, String> columnTypes = getColumnTypes(tableName);
        
        return "INSERT INTO " + getQualifiedTableName(tableName) + " (" +
               fieldNames.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) +
               ") VALUES (" +
               fieldNames.stream().map(field -> {
                   String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                   if (PostgresTypeOperator.isArrayType(columnType)) {
                       return ":" + field + "::" + columnType;
                   } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                       return ":" + field + "::interval";
                   } else if (columnType.contains("json")) {
                       return ":" + field + "::" + columnType;
                   } else if (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr")) {
                       return ":" + field + "::" + columnType;
                   } else if (columnType.contains("timestamp") || columnType.contains("time")) {
                       return ":" + field + "::" + columnType;
                   } else if (columnType.contains("xml")) {
                       return ":" + field + "::xml";
                   } else if (columnType.contains("bytea")) {
                       return ":" + field + "::bytea";
                   } else if (PostgresTypeOperator.isCustomEnumType(columnType)) {
                       return ":" + field + "::" + columnType;
                   } else if (PostgresTypeOperator.isCustomCompositeType(columnType)) {
                       return ":" + field + "::" + columnType;
                   } else {
                       return ":" + field;
                   }
               }).collect(Collectors.joining(", ")) +
               ") RETURNING *";
    }

    private String buildUpdateSql(String tableName, Set<String> updateFields, Set<String> whereFields) {
        Map<String, String> columnTypes = getColumnTypes(tableName);
        
        return "UPDATE " + getQualifiedTableName(tableName) + " SET " +
               updateFields.stream().map(field -> {
                   String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                   if (PostgresTypeOperator.isArrayType(columnType)) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                       return quoteIdentifier(field) + " = :" + field + "::interval";
                   } else if (columnType.contains("json")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("timestamp") || columnType.contains("time")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("xml")) {
                       return quoteIdentifier(field) + " = :" + field + "::xml";
                   } else if (columnType.contains("bytea")) {
                       return quoteIdentifier(field) + " = :" + field + "::bytea";
                   } else if (PostgresTypeOperator.isCustomEnumType(columnType)) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (PostgresTypeOperator.isCustomCompositeType(columnType)) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else {
                       return quoteIdentifier(field) + " = :" + field;
                   }
               }).collect(Collectors.joining(", ")) +
               " WHERE " +
               whereFields.stream().map(field -> {
                   String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                   if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                       return quoteIdentifier(field) + " = :" + field + "::interval";
                   } else if (columnType.contains("json")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("timestamp") || columnType.contains("time")) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else if (columnType.contains("xml")) {
                       return quoteIdentifier(field) + " = :" + field + "::xml";
                   } else if (columnType.contains("bytea")) {
                       return quoteIdentifier(field) + " = :" + field + "::bytea";
                   } else if (PostgresTypeOperator.isCustomEnumType(columnType)) {
                       return quoteIdentifier(field) + " = :" + field + "::" + columnType;
                   } else {
                       return quoteIdentifier(field) + " = :" + field;
                   }
               }).collect(Collectors.joining(" AND ")) +
               " RETURNING *";
    }

    private String buildBulkInsertSql(String tableName, Set<String> allFields, int recordCount) {
        Map<String, String> columnTypes = getColumnTypes(tableName);
        
        StringBuilder sql = new StringBuilder("INSERT INTO ")
            .append(getQualifiedTableName(tableName))
            .append(" (")
            .append(allFields.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
            .append(") VALUES ");
        
        for (int i = 0; i < recordCount; i++) {
            if (i > 0) sql.append(", ");
            final int index = i; // Make it final for use in stream
            sql.append("(")
               .append(allFields.stream().map(field -> {
                   String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
                   if (PostgresTypeOperator.isArrayType(columnType)) {
                       return ":" + field + "_" + index + "::" + columnType;
                   } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                       return ":" + field + "_" + index + "::interval";
                   } else if (columnType.contains("json")) {
                       return ":" + field + "_" + index + "::" + columnType;
                   } else if (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr")) {
                       return ":" + field + "_" + index + "::" + columnType;
                   } else if (columnType.contains("timestamp") || columnType.contains("time")) {
                       return ":" + field + "_" + index + "::" + columnType;
                   } else if (columnType.contains("xml")) {
                       return ":" + field + "_" + index + "::xml";
                   } else if (columnType.contains("bytea")) {
                       return ":" + field + "_" + index + "::bytea";
                   } else {
                       return ":" + field + "_" + index;
                   }
               }).collect(Collectors.joining(", ")))
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
        if (value == null || (value instanceof String && value.toString().isEmpty())) {
            paramSource.addValue(paramName, null);
            return;
        }

        try {
            // Handle array types
            if (PostgresTypeOperator.isArrayType(columnType)) {
                handleArrayParameter(paramSource, paramName, value, columnType);
                return;
            }

            // Handle custom composite types
            if (PostgresTypeOperator.isCustomCompositeType(columnType) && value instanceof Map) {
                String compositeValue = convertMapToPostgresComposite((Map<String, Object>) value);
                paramSource.addValue(paramName, compositeValue);
                return;
            }

            String valueStr = value.toString();
            
            if (columnType.contains(ColumnTypeConstant.UUID)) {
                paramSource.addValue(paramName, UUID.fromString(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                // For interval types, pass as string - PostgreSQL will handle the conversion
                paramSource.addValue(paramName, valueStr);
            } else if (columnType.contains(ColumnTypeConstant.INT) && !columnType.contains(ColumnTypeConstant.BIGINT)
                       && !columnType.equals(ColumnTypeConstant.INTERVAL)) {
                paramSource.addValue(paramName, Integer.parseInt(valueStr));
            } else if (columnType.contains(ColumnTypeConstant.BIGINT)) {
                paramSource.addValue(paramName, Long.parseLong(valueStr));
            } else if (PostgresTypeOperator.isFloatingPointType(columnType)) {
                paramSource.addValue(paramName, Double.parseDouble(valueStr));
            } else if (PostgresTypeOperator.isBooleanType(columnType)) {
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
            paramSource.addValue(paramName, value.toString());
        }
    }

    /**
     * Handles PostgreSQL array parameter conversion
     */
    private void handleArrayParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
        if (value == null) {
            paramSource.addValue(paramName, null);
            return;
        }

        try {
            // If value is already a List (from GraphQL), convert to PostgreSQL array format
            if (value instanceof List<?>) {
                List<?> listValue = (List<?>) value;
                
                if (listValue.isEmpty()) {
                    // For empty arrays, pass as null and let PostgreSQL handle the casting
                    paramSource.addValue(paramName, null);
                    return;
                }

                // Get the base type of the array for PostgreSQL
                String baseType = columnType.replace(ColumnTypeConstant.ARRAY_SUFFIX, "").toLowerCase();
                String pgBaseTypeName = mapToPGArrayTypeName(baseType);
                
                // Convert List elements to appropriate types
                Object[] convertedArray = convertListToTypedArray(listValue, baseType);
                
                // For PostgreSQL, we'll pass the array as a formatted string that PostgreSQL can parse
                // This approach works better with Spring JDBC and PostgreSQL casting
                String arrayString = formatArrayForPostgreSQL(convertedArray, pgBaseTypeName);
                paramSource.addValue(paramName, arrayString);
                
            } else if (value instanceof String) {
                // Handle string representation of array (fallback)
                String arrayStr = (String) value;
                if (arrayStr.startsWith("{") && arrayStr.endsWith("}")) {
                    // Already in PostgreSQL format, pass as-is
                    paramSource.addValue(paramName, arrayStr);
                } else {
                    log.warn("Unexpected array string format for {}: {}", paramName, arrayStr);
                    paramSource.addValue(paramName, arrayStr);
                }
            } else {
                log.warn("Unexpected array value type for {}: {}", paramName, value.getClass());
                paramSource.addValue(paramName, value);
            }
            
        } catch (Exception e) {
            log.error("Error handling array parameter {}: {}", paramName, e.getMessage());
            // Fallback to raw value
            paramSource.addValue(paramName, value);
        }
    }

    /**
     * Maps database base types to PostgreSQL array type names
     */
    private String mapToPGArrayTypeName(String baseType) {
        if (PostgresTypeOperator.isIntegerType(baseType)) {
            if (baseType.contains(ColumnTypeConstant.BIGINT)) {
                return "bigint";
            } else {
                return "integer";
            }
        } else if (PostgresTypeOperator.isFloatingPointType(baseType)) {
            if (baseType.contains("double") || baseType.contains("precision")) {
                return "double precision";
            } else {
                return "real";
            }
        } else if (PostgresTypeOperator.isBooleanType(baseType)) {
            return "boolean";
        } else if (baseType.contains("varchar") || baseType.contains("character varying")) {
            return "text"; // Use text for varchar arrays for simplicity
        } else if (baseType.contains("decimal") || baseType.contains("numeric")) {
            return "numeric";
        } else {
            return "text"; // Default to text for other types
        }
    }

    /**
     * Formats an array for PostgreSQL in string format
     */
    private String formatArrayForPostgreSQL(Object[] array, String baseType) {
        if (array == null || array.length == 0) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            
            Object element = array[i];
            if (element == null) {
                sb.append("NULL");
            } else if (needsQuoting(baseType)) {
                // Quote string-like values and escape internal quotes
                String elementStr = element.toString().replace("\"", "\\\"");
                sb.append("\"").append(elementStr).append("\"");
            } else {
                // Numeric and boolean values don't need quoting
                sb.append(element.toString());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Determines if array elements need quoting based on PostgreSQL type
     */
    private boolean needsQuoting(String pgTypeName) {
        return "text".equals(pgTypeName) || 
               pgTypeName.contains("varchar") || 
               pgTypeName.contains("char") ||
               pgTypeName.contains("uuid");
    }

    /**
     * Converts a Java List to a typed array for PostgreSQL
     */
    private Object[] convertListToTypedArray(List<?> listValue, String baseType) {
        return listValue.stream()
                .map(element -> convertArrayElement(element, baseType))
                .toArray();
    }

    /**
     * Converts individual array elements to the appropriate Java type
     */
    private Object convertArrayElement(Object element, String baseType) {
        if (element == null) {
            return null;
        }

        String elementStr = element.toString();
        
        try {
            if (PostgresTypeOperator.isIntegerType(baseType)) {
                if (baseType.contains(ColumnTypeConstant.BIGINT)) {
                    return Long.parseLong(elementStr);
                } else {
                    return Integer.parseInt(elementStr);
                }
            } else if (PostgresTypeOperator.isFloatingPointType(baseType)) {
                return Double.parseDouble(elementStr);
            } else if (PostgresTypeOperator.isBooleanType(baseType)) {
                return Boolean.parseBoolean(elementStr);
            } else if (baseType.contains(ColumnTypeConstant.UUID)) {
                return elementStr; // Keep UUIDs as strings for PostgreSQL
            } else {
                return elementStr; // Default to string
            }
        } catch (NumberFormatException e) {
            log.warn("Could not convert array element '{}' to type '{}', using as string", elementStr, baseType);
            return elementStr;
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

    /**
     * Converts a Map to PostgreSQL composite type format
     * E.g., {email: "john@example.com", phone: "555-1234"} -> "(john@example.com,555-1234)"
     */
    private String convertMapToPostgresComposite(Map<String, Object> compositeMap) {
        if (compositeMap == null || compositeMap.isEmpty()) {
            return null;
        }

        // Convert map values to a comma-separated string wrapped in parentheses
        String values = compositeMap.values().stream()
                .map(value -> {
                    if (value == null) {
                        return "";
                    }
                    String strValue = value.toString();
                    // Escape quotes and wrap in quotes if needed
                    if (strValue.contains(",") || strValue.contains("\"") || strValue.contains("(") || strValue.contains(")")) {
                        return "\"" + strValue.replace("\"", "\\\"") + "\"";
                    }
                    return strValue;
                })
                .collect(Collectors.joining(","));

        return "(" + values + ")";
    }
} 