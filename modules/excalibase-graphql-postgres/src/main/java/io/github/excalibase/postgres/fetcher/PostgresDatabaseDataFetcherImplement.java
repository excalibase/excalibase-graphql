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
package io.github.excalibase.postgres.fetcher;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.FieldConstant;
import io.github.excalibase.postgres.constant.PostgresSqlSyntaxConstant;
import io.github.excalibase.postgres.constant.PostgresErrorConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataFetcherException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.postgres.util.PostgresSchemaHelper;
import io.github.excalibase.postgres.util.PostgresSqlBuilder;
import io.github.excalibase.postgres.util.PostgresTypeConverter;
import io.github.excalibase.schema.fetcher.IDatabaseDataFetcher;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Refactored PostgreSQL implementation of IDatabaseDataFetcher using utility classes.
 */
@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseDataFetcherImplement implements IDatabaseDataFetcher {
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseDataFetcherImplement.class);
    
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ServiceLookup serviceLookup;
    private final AppConfig appConfig;
    private IDatabaseSchemaReflector schemaReflector;
    
    // Utility classes (lazy initialization)
    private PostgresSchemaHelper schemaHelper;
    private PostgresSqlBuilder sqlBuilder;
    private PostgresTypeConverter typeConverter;

    private static final String BATCH_CONTEXT = "BATCH_CONTEXT";
    private static final String CURSOR_ERROR = PostgresErrorConstant.CURSOR_REQUIRED_ERROR;

    public PostgresDatabaseDataFetcherImplement(JdbcTemplate jdbcTemplate, 
                                                         NamedParameterJdbcTemplate namedParameterJdbcTemplate, 
                                                         ServiceLookup serviceLookup, 
                                                         AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
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

    @Override
    public DataFetcher<List<Map<String, Object>>> createTableDataFetcher(String tableName) {
        return environment -> {
            // Get table info
            Map<String, TableInfo> tables = getSchemaHelper().getAllTables();
            TableInfo tableInfo = tables.get(tableName);

            // Get all available column names for this table
            List<String> availableColumns = getSchemaHelper().getAvailableColumns(tableName);

            // Filter out relationship fields and keep only direct columns
            List<String> requestedFields = environment.getSelectionSet().getFields().stream()
                    .map(SelectedField::getName)
                    .filter(availableColumns::contains) // Only include fields that exist as columns
                    .collect(Collectors.toList());

            Set<String> relationshipFields = environment.getSelectionSet().getFields().stream()
                    .filter(field -> !availableColumns.contains(field.getName()))
                    .map(SelectedField::getName)
                    .collect(Collectors.toSet());

            if (requestedFields.isEmpty() && !availableColumns.isEmpty()) {
                requestedFields = new ArrayList<>(availableColumns);
            }

            StringBuilder sql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_WITH_SPACE);
            sql.append(getSqlBuilder().buildColumnList(requestedFields));
            sql.append(PostgresSqlSyntaxConstant.FROM_WITH_SPACE)
               .append(getSqlBuilder().getQualifiedTableName(tableName, appConfig.getAllowedSchema()));

            Map<String, Object> arguments = new HashMap<>(environment.getArguments());
            MapSqlParameterSource paramSource = new MapSqlParameterSource();

            Map<String, String> orderByFields = new HashMap<>();
            if (arguments.containsKey(FieldConstant.ORDER_BY)) {
                Map<String, Object> orderByArg = (Map<String, Object>) arguments.get(FieldConstant.ORDER_BY);
                for (Map.Entry<String, Object> entry : orderByArg.entrySet()) {
                    orderByFields.put(entry.getKey(), entry.getValue().toString());
                }
                arguments.remove(FieldConstant.ORDER_BY);
            }

            Integer limit = null;
            Integer offset = null;

            if (arguments.containsKey(FieldConstant.LIMIT)) {
                limit = (Integer) arguments.get(FieldConstant.LIMIT);
                arguments.remove(FieldConstant.LIMIT);
            }

            if (arguments.containsKey(FieldConstant.OFFSET)) {
                offset = (Integer) arguments.get(FieldConstant.OFFSET);
                arguments.remove(FieldConstant.OFFSET);
            }
            
            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            
            if (!arguments.isEmpty()) {
                List<String> conditions = getSqlBuilder().buildWhereConditions(arguments, paramSource, columnTypes);

                if (!conditions.isEmpty()) {
                    sql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                       .append(String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, conditions));
                }
            }

            if (!orderByFields.isEmpty()) {
                sql.append(PostgresSqlSyntaxConstant.ORDER_BY_WITH_SPACE);
                List<String> orderClauses = new ArrayList<>();

                for (Map.Entry<String, String> entry : orderByFields.entrySet()) {
                    if (availableColumns.contains(entry.getKey())) {
                        orderClauses.add(getSqlBuilder().quoteIdentifier(entry.getKey()) + " " + entry.getValue());
                    }
                }

                sql.append(String.join(", ", orderClauses));
            }

            // Add pagination if specified using LIMIT and OFFSET
            if (limit != null) {
                sql.append(PostgresSqlSyntaxConstant.LIMIT_WITH_SPACE + " :limit");
                paramSource.addValue(FieldConstant.LIMIT, limit);
            }

            if (offset != null) {
                sql.append(PostgresSqlSyntaxConstant.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }

            // After executing the query and getting results, we need to process PostgreSQL arrays
            List<Map<String, Object>> results = namedParameterJdbcTemplate.queryForList(sql.toString(), paramSource);
            
            // Convert PostgreSQL arrays and custom types for GraphQL compatibility
            if (tableInfo != null) {
                results = getTypeConverter().convertPostgresTypesToGraphQLTypes(results, tableInfo);
            }

            if (!relationshipFields.isEmpty() && !results.isEmpty()) {
                preloadRelationships(environment, tableName, relationshipFields, results);
            }
            return results;
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> createConnectionDataFetcher(String tableName) {
        return environment -> {
            Map<String, TableInfo> tables = getSchemaHelper().getAllTables();
            TableInfo tableInfo = tables.get(tableName);

            List<String> availableColumns = getSchemaHelper().getAvailableColumns(tableName);

            List<String> requestedFields = environment.getSelectionSet().getFields().stream()
                    .map(SelectedField::getName)
                    .filter(field -> !field.equals(FieldConstant.EDGES) && !field.equals(FieldConstant.PAGE_INFO) && !field.equals(FieldConstant.TOTAL_COUNT))
                    .filter(availableColumns::contains)
                    .collect(Collectors.toList());

            Set<String> relationshipFields = environment.getSelectionSet().getFields().stream()
                    .filter(field -> field.getName().equals(FieldConstant.EDGES))
                    .filter(field -> field.getSelectionSet() != null)
                    .flatMap(field -> field.getSelectionSet().getFields().stream())
                    .filter(field -> field.getName().equals(FieldConstant.NODE))
                    .filter(field -> field.getSelectionSet() != null)
                    .flatMap(field -> field.getSelectionSet().getFields().stream())
                    .map(SelectedField::getName)
                    .filter(name -> !availableColumns.contains(name))
                    .collect(Collectors.toSet());

            if (requestedFields.isEmpty() && !availableColumns.isEmpty()) {
                requestedFields = new ArrayList<>(availableColumns);
            }

            Map<String, Object> arguments = new HashMap<>(environment.getArguments());

            Map<String, String> orderByFields = new HashMap<>();
            if (arguments.containsKey(FieldConstant.ORDER_BY)) {
                Map<String, Object> orderByArg = (Map<String, Object>) arguments.get(FieldConstant.ORDER_BY);
                for (Map.Entry<String, Object> entry : orderByArg.entrySet()) {
                    orderByFields.put(entry.getKey(), entry.getValue().toString());
                }
                arguments.remove(FieldConstant.ORDER_BY);
            }

            Integer first = null;
            String after = null;
            Integer last = null;
            String before = null;
            Integer offset = null;

            if (arguments.containsKey(FieldConstant.FIRST)) {
                first = (Integer) arguments.get(FieldConstant.FIRST);
                arguments.remove(FieldConstant.FIRST);
            }

            if (arguments.containsKey(FieldConstant.AFTER)) {
                after = (String) arguments.get(FieldConstant.AFTER);
                arguments.remove(FieldConstant.AFTER);
            }

            if (arguments.containsKey(FieldConstant.LAST)) {
                last = (Integer) arguments.get(FieldConstant.LAST);
                arguments.remove(FieldConstant.LAST);
            }

            if (arguments.containsKey(FieldConstant.BEFORE)) {
                before = (String) arguments.get(FieldConstant.BEFORE);
                arguments.remove(FieldConstant.BEFORE);
            }

            // Offset-based pagination parameter (fallback when no cursor parameters)
            if (arguments.containsKey(FieldConstant.OFFSET)) {
                offset = (Integer) arguments.get(FieldConstant.OFFSET);
                arguments.remove(FieldConstant.OFFSET);
            }

            //TODO this maybe not good practice, need to revisit this
            boolean useCursorPagination = offset == null && (first != null || last != null || after != null || before != null);
            boolean useOffsetPagination = !useCursorPagination && offset != null;

            Map<String, String> columnTypes = getSchemaHelper().getColumnTypes(tableName);
            MapSqlParameterSource paramSource = new MapSqlParameterSource();

            StringBuilder countSql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_COUNT_FROM_WITH_SPACE)
                    .append(getSqlBuilder().getQualifiedTableName(tableName, appConfig.getAllowedSchema()));

            if (!arguments.isEmpty()) {
                List<String> conditions = getSqlBuilder().buildWhereConditions(arguments, paramSource, columnTypes);

                if (!conditions.isEmpty()) {
                    countSql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                            .append(String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, conditions));
                }
            }

            Integer totalCount = namedParameterJdbcTemplate.queryForObject(countSql.toString(), paramSource, Integer.class);

            StringBuilder dataSql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_WITH_SPACE);
            dataSql.append(getSqlBuilder().buildColumnList(availableColumns));
            dataSql.append(PostgresSqlSyntaxConstant.FROM_WITH_SPACE)
                   .append(getSqlBuilder().getQualifiedTableName(tableName, appConfig.getAllowedSchema()));

            List<String> conditions = new ArrayList<>();

            if (!arguments.isEmpty()) {
                conditions.addAll(getSqlBuilder().buildWhereConditions(arguments, paramSource, columnTypes));
            }

            if (useCursorPagination && after != null) {
                try {
                    String decodedCursor = new String(Base64.getDecoder().decode(after));
                    String[] cursorParts = decodedCursor.split("\\|");
                    List<String> cursorConditions = new ArrayList<>();
                    Map<String, Object> cursorValues = new HashMap<>();

                    for (String part : cursorParts) {
                        String[] fieldValue = part.split(":", 2);
                        if (fieldValue.length == 2) {
                            String fieldName = fieldValue[0];
                            String value = fieldValue[1];
                            if (orderByFields.containsKey(fieldName)) {
                                cursorValues.put(fieldName, value);
                            }
                        }
                    }
                    if (!cursorValues.isEmpty()) {
                        getSqlBuilder().buildCursorConditions(cursorConditions, cursorValues, orderByFields, columnTypes, paramSource, "after");
                        conditions.addAll(cursorConditions);
                    }

                } catch (Exception e) {
                    log.error("Error processing 'after' cursor: {}", e.getMessage());
                    throw new DataFetcherException("Invalid cursor format for 'after': " + after, e);
                }
            }

            if (useCursorPagination && before != null) {
                try {
                    String decodedCursor = new String(Base64.getDecoder().decode(before));
                    String[] cursorParts = decodedCursor.split("\\|");

                    // Build conditions based on the cursor values and orderBy fields
                    List<String> cursorConditions = new ArrayList<>();
                    Map<String, Object> cursorValues = new HashMap<>();

                    // Parse cursor parts into field:value pairs
                    for (String part : cursorParts) {
                        String[] fieldValue = part.split(":", 2);
                        if (fieldValue.length == 2) {
                            String fieldName = fieldValue[0];
                            String value = fieldValue[1];

                            // Only process if this field is in our orderBy
                            if (orderByFields.containsKey(fieldName)) {
                                cursorValues.put(fieldName, value);
                            }
                        }
                    }

                    if (!cursorValues.isEmpty()) {
                        getSqlBuilder().buildCursorConditions(cursorConditions, cursorValues, orderByFields, columnTypes, paramSource, "before");
                        conditions.addAll(cursorConditions);
                    }

                } catch (Exception e) {
                    log.error("Error processing 'before' cursor: {}", e.getMessage());
                    throw new DataFetcherException("Invalid cursor format for 'before': " + before, e);
                }
            }

            if (!conditions.isEmpty()) {
                dataSql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                       .append(String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, conditions));
            }

            // Add ORDER BY based on orderBy parameter or default ordering
            if (!orderByFields.isEmpty()) {
                dataSql.append(PostgresSqlSyntaxConstant.ORDER_BY_WITH_SPACE);
                List<String> orderClauses = new ArrayList<>();

                for (Map.Entry<String, String> entry : orderByFields.entrySet()) {
                    if (availableColumns.contains(entry.getKey())) {
                        orderClauses.add(getSqlBuilder().quoteIdentifier(entry.getKey()) + " " + entry.getValue());
                    }
                }

                dataSql.append(String.join(", ", orderClauses));
            } else if (useCursorPagination) {
                // For cursor pagination without explicit orderBy, default to all primary key columns
                if (tableInfo != null) {
                    List<String> primaryKeyColumns = tableInfo.getColumns().stream()
                        .filter(ColumnInfo::isPrimaryKey)
                        .map(ColumnInfo::getName)
                        .filter(availableColumns::contains)
                        .toList();
                        
                    if (!primaryKeyColumns.isEmpty()) {
                        List<String> orderClauses = primaryKeyColumns.stream()
                            .map(col -> getSqlBuilder().quoteIdentifier(col) + " ASC")
                            .toList();
                        dataSql.append(PostgresSqlSyntaxConstant.ORDER_BY_WITH_SPACE).append(String.join(", ", orderClauses));
                        
                        // Add all primary keys to orderByFields for cursor generation
                        for (String pkCol : primaryKeyColumns) {
                            orderByFields.put(pkCol, "ASC");
                        }
                    } else if (availableColumns.contains("id")) {
                        // Fallback to 'id' if available
                        dataSql.append(PostgresSqlSyntaxConstant.ORDER_BY_WITH_SPACE)
                               .append(getSqlBuilder().quoteIdentifier("id")).append(" ASC");
                        orderByFields.put("id", "ASC");
                    }
                } else if (availableColumns.contains("id")) {
                    // Fallback when no table info
                    dataSql.append(PostgresSqlSyntaxConstant.ORDER_BY_WITH_SPACE)
                           .append(getSqlBuilder().quoteIdentifier("id")).append(" ASC");
                    orderByFields.put("id", "ASC");
                }
            }

            Integer limit = first != null ? first : (last != null ? last : 10); // Default to 10 if neither specified
            dataSql.append(PostgresSqlSyntaxConstant.LIMIT_WITH_SPACE + ":limit");
            paramSource.addValue(FieldConstant.LIMIT, limit);

            // Add OFFSET for offset-based pagination
            if (useOffsetPagination) {
                dataSql.append(PostgresSqlSyntaxConstant.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }
            
            List<Map<String, Object>> nodes = namedParameterJdbcTemplate.queryForList(dataSql.toString(), paramSource);
            
            // Convert PostgreSQL arrays and custom types for GraphQL compatibility
            if (tableInfo != null) {
                nodes = getTypeConverter().convertPostgresTypesToGraphQLTypes(nodes, tableInfo);
            }

            if (!relationshipFields.isEmpty() && !nodes.isEmpty()) {
                preloadRelationships(environment, tableName, relationshipFields, nodes);
            }

            List<Map<String, Object>> edges = nodes.stream().map(node -> {
                Map<String, Object> edge = new HashMap<>();
                edge.put(FieldConstant.NODE, node);

                if (useCursorPagination && !orderByFields.isEmpty()) {
                    List<String> cursorParts = new ArrayList<>();
                    for (String orderField : orderByFields.keySet()) {
                        Object fieldValue = node.get(orderField);
                        String valueStr = fieldValue != null ? fieldValue.toString() : "";
                        cursorParts.add(orderField + ":" + valueStr);
                    }

                    String cursorData = String.join("|", cursorParts);
                    String cursor = Base64.getEncoder().encodeToString(cursorData.getBytes());
                    edge.put(FieldConstant.CURSOR, cursor);
                } else {
                    // For offset-based pagination, cursor can be null or a simple index
                    String cursor = null;
                    if (useCursorPagination) {
                        cursor = "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument.";
                    }
                    edge.put(FieldConstant.CURSOR, cursor);
                }

                return edge;
            }).collect(Collectors.toList());

            Map<String, Object> pageInfo = new HashMap<>();
            boolean hasNextPage = false;
            boolean hasPreviousPage = false;

            if (!edges.isEmpty()) {
                String startCursor = (String) edges.getFirst().get(FieldConstant.CURSOR);
                String endCursor = (String) edges.getLast().get(FieldConstant.CURSOR);

                pageInfo.put(FieldConstant.START_CURSOR, startCursor);
                pageInfo.put(FieldConstant.END_CURSOR, endCursor);

                if (useCursorPagination && !orderByFields.isEmpty()) {
                    Map<String, Object> lastRecordCursor = new HashMap<>();
                    for (String orderField : orderByFields.keySet()) {
                        lastRecordCursor.put(orderField, nodes.getLast().get(orderField));
                    }

                    StringBuilder checkNextSql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_COUNT_FROM_WITH_SPACE)
                            .append(getSqlBuilder().getQualifiedTableName(tableName, appConfig.getAllowedSchema()));

                    List<String> nextConditions = new ArrayList<>();
                    MapSqlParameterSource nextParams = new MapSqlParameterSource();
                    if (!arguments.isEmpty()) {
                        nextConditions.addAll(getSqlBuilder().buildWhereConditions(arguments, nextParams, columnTypes));
                    }

                    List<String> cursorConditions = new ArrayList<>();
                    getSqlBuilder().buildCursorConditions(cursorConditions, lastRecordCursor, orderByFields, columnTypes, nextParams, "after");
                    nextConditions.addAll(cursorConditions);

                    if (!nextConditions.isEmpty()) {
                        checkNextSql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                                   .append(String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, nextConditions));
                    }

                    Integer nextCount = namedParameterJdbcTemplate.queryForObject(checkNextSql.toString(), nextParams, Integer.class);
                    hasNextPage = nextCount != null && nextCount > 0;

                    // Check if there's a previous page by simulating a "before" cursor with the first record
                    Map<String, Object> firstRecordCursor = new HashMap<>();
                    for (String orderField : orderByFields.keySet()) {
                        firstRecordCursor.put(orderField, nodes.getFirst().get(orderField));
                    }

                    StringBuilder checkPrevSql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_COUNT_FROM_WITH_SPACE)
                            .append(getSqlBuilder().getQualifiedTableName(tableName, appConfig.getAllowedSchema()));

                    List<String> prevConditions = new ArrayList<>();
                    MapSqlParameterSource prevParams = new MapSqlParameterSource();

                    if (!arguments.isEmpty()) {
                        prevConditions.addAll(getSqlBuilder().buildWhereConditions(arguments, prevParams, columnTypes));
                    }

                    List<String> cursorConditions2 = new ArrayList<>();
                    getSqlBuilder().buildCursorConditions(cursorConditions2, firstRecordCursor, orderByFields, columnTypes, prevParams, "before");
                    prevConditions.addAll(cursorConditions2);

                    if (!prevConditions.isEmpty()) {
                        checkPrevSql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                                   .append(String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, prevConditions));
                    }

                    Integer prevCount = namedParameterJdbcTemplate.queryForObject(checkPrevSql.toString(), prevParams, Integer.class);
                    hasPreviousPage = prevCount != null && prevCount > 0;
                }
            }

            pageInfo.put(FieldConstant.HAS_NEXT_PAGE, hasNextPage);
            pageInfo.put(FieldConstant.HAS_PREVIOUS_PAGE, hasPreviousPage);

            // Build the final connection result following the Relay Connection spec
            Map<String, Object> connection = new HashMap<>();
            connection.put(FieldConstant.EDGES, edges);
            connection.put(FieldConstant.PAGE_INFO, pageInfo);
            connection.put(FieldConstant.TOTAL_COUNT, totalCount != null ? totalCount : 0);
            return connection;
        };
    }

    @Override
    public DataFetcher<Map<String, Object>> createRelationshipDataFetcher(String tableName, String foreignKeyColumn, String referencedTable, String referencedColumn) {
        return environment -> {
            Map<String, Object> source = environment.getSource();
            Object foreignKeyValue = source.get(foreignKeyColumn);
            if (foreignKeyValue == null) {
                return null;
            }
            Map<String, Object> batchContext = environment.getGraphQlContext().get(BATCH_CONTEXT);

            if (batchContext != null && batchContext.containsKey(referencedTable)) {
                Map<Object, Map<String, Object>> batchResults =
                        (Map<Object, Map<String, Object>>) batchContext.get(referencedTable);
                return batchResults.get(foreignKeyValue);
            } else {
                log.warn("Batch context not found for table: {}. Fallback to individual query", referencedTable);
                
                Set<String> availableColumns = getSchemaHelper().getAvailableColumnsAsSet(referencedTable);

                // Filter requested fields to only include actual database columns (not nested relationships)
                List<String> requestedFields = environment.getSelectionSet().getFields().stream()
                        .map(SelectedField::getName)
                        .filter(availableColumns::contains) // Only include fields that exist as columns
                        .collect(Collectors.toList());
                
                // If no specific fields requested, fetch all available columns
                if (requestedFields.isEmpty() && !availableColumns.isEmpty()) {
                    requestedFields = new ArrayList<>(availableColumns);
                }
                
                // Always ensure the referenced column is included for proper relationship mapping
                if (!requestedFields.contains(referencedColumn)) {
                    requestedFields.add(referencedColumn);
                }

                StringBuilder sql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_WITH_SPACE);
                sql.append(getSqlBuilder().buildColumnList(requestedFields));
                sql.append(PostgresSqlSyntaxConstant.FROM_WITH_SPACE)
                   .append(getSqlBuilder().getQualifiedTableName(referencedTable, appConfig.getAllowedSchema()));
                sql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
                   .append(getSqlBuilder().quoteIdentifier(referencedColumn)).append(" = ?");
                   
                try {
                    return jdbcTemplate.queryForMap(sql.toString(), foreignKeyValue);
                } catch (Exception e) {
                    log.error("Error fetching relationship: {}", e.getMessage());
                    throw new DataFetcherException("Error fetching relationship for " + referencedTable + ": " + e.getMessage(), e);
                }
            }
        };
    }
    
    @Override
    public DataFetcher<List<Map<String, Object>>> createReverseRelationshipDataFetcher(
            String sourceTableName, String targetTableName, String foreignKeyColumn, String referencedColumn) {
        return environment -> {
            Map<String, Object> source = environment.getSource();
            Object referencedValue = source.get(referencedColumn);
            if (referencedValue == null) {
                return List.of();
            }
            
            Map<String, TableInfo> tables = getSchemaHelper().getAllTables();
            TableInfo targetTableInfo = tables.get(targetTableName);
            Set<String> availableColumns = getSchemaHelper().getAvailableColumnsAsSet(targetTableName);

            // Filter requested fields to only include actual database columns (not nested relationships)
            List<String> requestedFields = environment.getSelectionSet().getFields().stream()
                    .map(SelectedField::getName)
                    .filter(availableColumns::contains) // Only include fields that exist as columns
                    .collect(Collectors.toList());

            if (requestedFields.isEmpty() && !availableColumns.isEmpty()) {
                requestedFields = new ArrayList<>(availableColumns);
            }

            StringBuilder sql = new StringBuilder(PostgresSqlSyntaxConstant.SELECT_WITH_SPACE);
            sql.append(getSqlBuilder().buildColumnList(requestedFields));
            sql.append(PostgresSqlSyntaxConstant.FROM_WITH_SPACE)
               .append(getSqlBuilder().getQualifiedTableName(targetTableName, appConfig.getAllowedSchema()));
            sql.append(PostgresSqlSyntaxConstant.WHERE_WITH_SPACE)
               .append(getSqlBuilder().quoteIdentifier(foreignKeyColumn)).append(" = ?");
            
            try {
                List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), referencedValue);
                
                // Convert PostgreSQL arrays to Java Lists for GraphQL compatibility
                if (targetTableInfo != null) {
                    results = getTypeConverter().convertPostgresTypesToGraphQLTypes(results, targetTableInfo);
                }
                
                return results;
            } catch (Exception e) {
                log.error("Error fetching reverse relationship from {} to {}: {}", sourceTableName, targetTableName, e.getMessage());
                throw new DataFetcherException("Error fetching reverse relationship from " + sourceTableName + " to " + targetTableName + ": " + e.getMessage(), e);
            }
        };
    }

    private IDatabaseSchemaReflector getSchemaReflector() {
        if (schemaReflector != null) {
            return schemaReflector;
        }
        schemaReflector = serviceLookup.forBean(IDatabaseSchemaReflector.class, appConfig.getDatabaseType().getName());
        return schemaReflector;
    }

    private void preloadRelationships(
            DataFetchingEnvironment environment,
            String tableName,
            Set<String> relationshipFields,
            List<Map<String, Object>> results) {

        Map<String, TableInfo> tables = getSchemaHelper().getAllTables();
        TableInfo tableInfo = tables.get(tableName);

        // If table info is null, we can't preload relationships
        if (tableInfo == null) {
            log.warn("Table info not found for table: {}. Cannot preload relationships.", tableName);
            return;
        }

        // Get batch context or create it
        Map<String, Object> batchContext = environment.getGraphQlContext().get(BATCH_CONTEXT);
        if (batchContext == null) {
            batchContext = new HashMap<>();
            environment.getGraphQlContext().put(BATCH_CONTEXT, batchContext);
        }

        // Process each relationship field
        for (String relationshipField : relationshipFields) {
            // Find foreign key info for this relationship
            Optional<ForeignKeyInfo> fkInfo = tableInfo.getForeignKeys().stream()
                    .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(relationshipField))
                    .findFirst();

            if (fkInfo.isPresent()) {
                String foreignKeyColumn = fkInfo.get().getColumnName();
                String referencedTable = fkInfo.get().getReferencedTable();
                String referencedColumn = fkInfo.get().getReferencedColumn();

                // Collect all foreign key values
                Set<Object> foreignKeyValues = results.stream()
                        .map(row -> row.get(foreignKeyColumn))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                if (!foreignKeyValues.isEmpty()) {
                    List<SelectedField> selectedFields = environment.getSelectionSet().getFields().stream()
                            .filter(field -> field.getName().equals(relationshipField))
                            .findFirst()
                            .map(field -> field.getSelectionSet() != null ? field.getSelectionSet().getFields() : List.<SelectedField>of())
                            .orElse(List.of());

                    Set<String> availableColumns = getSchemaHelper().getAvailableColumnsAsSet(referencedTable);

                    // If referenced table doesn't exist, skip this relationship
                    if (availableColumns == null || availableColumns.isEmpty()) {
                        log.warn("Referenced table '{}' not found or has no columns. Skipping relationship preload.", referencedTable);
                        continue;
                    }

                    // Filter requested fields to only include actual database columns (not nested relationships)
                    Set<String> requestedFields = selectedFields.stream()
                            .map(SelectedField::getName)
                            .filter(availableColumns::contains) // Only include fields that exist as columns
                            .collect(Collectors.toSet());

                    if (!requestedFields.isEmpty()) {
                        // Always include the reference column for proper mapping
                        requestedFields.add(referencedColumn);

                        // Build and execute batch query
                        String sql = PostgresSqlSyntaxConstant.SELECT_WITH_SPACE + getSqlBuilder().buildColumnList(new ArrayList<>(requestedFields)) +
                                PostgresSqlSyntaxConstant.FROM_WITH_SPACE + getSqlBuilder().getQualifiedTableName(referencedTable, appConfig.getAllowedSchema()) +
                                PostgresSqlSyntaxConstant.WHERE_WITH_SPACE + getSqlBuilder().quoteIdentifier(referencedColumn) + PostgresSqlSyntaxConstant.IN_WITH_SPACE + "(:ids)";

                        MapSqlParameterSource params = new MapSqlParameterSource();
                        params.addValue("ids", new ArrayList<>(foreignKeyValues));

                        List<Map<String, Object>> relatedRecords = namedParameterJdbcTemplate.queryForList(
                                sql, params);

                        // cache the related records in the batch context so we don't have to query again
                        Map<Object, Map<String, Object>> relatedRecordsMap = new HashMap<>();
                        for (Map<String, Object> record : relatedRecords) {
                            relatedRecordsMap.put(record.get(referencedColumn), record);
                        }
                        batchContext.put(referencedTable, relatedRecordsMap);
                    }
                }
            }
        }
    }
}