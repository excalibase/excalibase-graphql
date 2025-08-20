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
import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.constant.FieldConstant;
import io.github.excalibase.postgres.constant.PostgresTypeOperator;
import io.github.excalibase.postgres.constant.PostgresColumnTypeConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataFetcherException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.CompositeTypeAttribute;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.fetcher.IDatabaseDataFetcher;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
// PGobject import removed - using alternative approach
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL implementation of IDatabaseDataFetcher.
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

    private static final String BATCH_CONTEXT = "BATCH_CONTEXT";
    private static final String CURSOR_ERROR = "orderBy parameter is required for cursor-based pagination";

    public PostgresDatabaseDataFetcherImplement(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate, ServiceLookup serviceLookup, AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.serviceLookup = serviceLookup;
        this.appConfig = appConfig;
    }

    @Override
    public DataFetcher<List<Map<String, Object>>> createTableDataFetcher(String tableName) {
        return environment -> {
            // Get table info
            Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);

            // Get all available column names for this table
            List<String> availableColumns = tableInfo != null
                    ? tableInfo.getColumns().stream()
                    .map(ColumnInfo::getName)
                    .toList()
                    : new ArrayList<>();

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

            StringBuilder sql = new StringBuilder(PostgresColumnTypeConstant.SELECT_WITH_SPACE);
            sql.append(buildColumnList(requestedFields));
            sql.append(PostgresColumnTypeConstant.FROM_WITH_SPACE).append(getQualifiedTableName(tableName));

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
            Map<String, String> columnTypes = getColumnTypes(tableName);
            if (!arguments.isEmpty()) {
                List<String> conditions = buildWhereConditions(arguments, paramSource, columnTypes);

                if (!conditions.isEmpty()) {
                    sql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, conditions));
                }
            }

            if (!orderByFields.isEmpty()) {
                sql.append(PostgresColumnTypeConstant.ORDER_BY_WITH_SPACE);
                List<String> orderClauses = new ArrayList<>();

                for (Map.Entry<String, String> entry : orderByFields.entrySet()) {
                    if (availableColumns.contains(entry.getKey())) {
                        orderClauses.add(quoteIdentifier(entry.getKey()) + " " + entry.getValue());
                    }
                }

                sql.append(String.join(", ", orderClauses));
            }

            // Add pagination if specified using LIMIT and OFFSET
            if (limit != null) {
                sql.append(PostgresColumnTypeConstant.LIMIT_WITH_SPACE + " :limit");
                paramSource.addValue(FieldConstant.LIMIT, limit);
            }

            if (offset != null) {
                sql.append(PostgresColumnTypeConstant.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }

            // After executing the query and getting results, we need to process PostgreSQL arrays
            List<Map<String, Object>> results = namedParameterJdbcTemplate.queryForList(sql.toString(), paramSource);
            
            // Convert PostgreSQL arrays and custom types for GraphQL compatibility
            if (tableInfo != null) {
                results = convertPostgresTypesToGraphQLTypes(results, tableInfo);
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
            Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
            TableInfo tableInfo = tables.get(tableName);

            List<String> availableColumns = tableInfo != null
                    ? tableInfo.getColumns().stream()
                    .map(ColumnInfo::getName)
                    .collect(Collectors.toList())
                    : new ArrayList<>();

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

            Map<String, String> columnTypes = getColumnTypes(tableName);
            MapSqlParameterSource paramSource = new MapSqlParameterSource();

            StringBuilder countSql = new StringBuilder(PostgresColumnTypeConstant.SELECT_COUNT_FROM_WITH_SPACE)
                    .append(getQualifiedTableName(tableName));

            if (!arguments.isEmpty()) {
                List<String> conditions = buildWhereConditions(arguments, paramSource, columnTypes);

                if (!conditions.isEmpty()) {
                    countSql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, conditions));
                }
            }

            Integer totalCount = namedParameterJdbcTemplate.queryForObject(countSql.toString(), paramSource, Integer.class);

            StringBuilder dataSql = new StringBuilder(PostgresColumnTypeConstant.SELECT_WITH_SPACE);
            dataSql.append(buildColumnList(availableColumns));
            dataSql.append(PostgresColumnTypeConstant.FROM_WITH_SPACE).append(getQualifiedTableName(tableName));

            List<String> conditions = new ArrayList<>();

            if (!arguments.isEmpty()) {
                conditions.addAll(buildWhereConditions(arguments, paramSource, columnTypes));
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
                        buildCursorConditions(cursorConditions, cursorValues, orderByFields, columnTypes, paramSource, "after");
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
                        buildCursorConditions(cursorConditions, cursorValues, orderByFields, columnTypes, paramSource, "before");
                        conditions.addAll(cursorConditions);
                    }

                } catch (Exception e) {
                    log.error("Error processing 'before' cursor: {}", e.getMessage());
                    throw new DataFetcherException("Invalid cursor format for 'before': " + before, e);
                }
            }

            if (!conditions.isEmpty()) {
                dataSql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, conditions));
            }

            // Add ORDER BY based on orderBy parameter or default ordering
            if (!orderByFields.isEmpty()) {
                dataSql.append(PostgresColumnTypeConstant.ORDER_BY_WITH_SPACE);
                List<String> orderClauses = new ArrayList<>();

                for (Map.Entry<String, String> entry : orderByFields.entrySet()) {
                    if (availableColumns.contains(entry.getKey())) {
                        orderClauses.add(quoteIdentifier(entry.getKey()) + " " + entry.getValue());
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
                            .map(col -> quoteIdentifier(col) + " ASC")
                            .toList();
                        dataSql.append(PostgresColumnTypeConstant.ORDER_BY_WITH_SPACE).append(String.join(", ", orderClauses));
                        
                        // Add all primary keys to orderByFields for cursor generation
                        for (String pkCol : primaryKeyColumns) {
                            orderByFields.put(pkCol, "ASC");
                        }
                    } else if (availableColumns.contains("id")) {
                        // Fallback to 'id' if available
                        dataSql.append(PostgresColumnTypeConstant.ORDER_BY_WITH_SPACE).append(quoteIdentifier("id")).append(" ASC");
                        orderByFields.put("id", "ASC");
                    }
                } else if (availableColumns.contains("id")) {
                    // Fallback when no table info
                    dataSql.append(PostgresColumnTypeConstant.ORDER_BY_WITH_SPACE).append(quoteIdentifier("id")).append(" ASC");
                    orderByFields.put("id", "ASC");
                }
            }

            Integer limit = first != null ? first : (last != null ? last : 10); // Default to 10 if neither specified
            dataSql.append(PostgresColumnTypeConstant.LIMIT_WITH_SPACE + ":limit");
            paramSource.addValue(FieldConstant.LIMIT, limit);

            // Add OFFSET for offset-based pagination
            if (useOffsetPagination) {
                dataSql.append(PostgresColumnTypeConstant.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }
            List<Map<String, Object>> nodes = namedParameterJdbcTemplate.queryForList(dataSql.toString(), paramSource);
            
            // Convert PostgreSQL arrays and custom types for GraphQL compatibility
            if (tableInfo != null) {
                nodes = convertPostgresTypesToGraphQLTypes(nodes, tableInfo);
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

                    StringBuilder checkNextSql = new StringBuilder(PostgresColumnTypeConstant.SELECT_COUNT_FROM_WITH_SPACE)
                            .append(getQualifiedTableName(tableName));

                    List<String> nextConditions = new ArrayList<>();
                    MapSqlParameterSource nextParams = new MapSqlParameterSource();
                    if (!arguments.isEmpty()) {
                        nextConditions.addAll(buildWhereConditions(arguments, nextParams, columnTypes));
                    }

                    List<String> cursorConditions = new ArrayList<>();
                    buildCursorConditions(cursorConditions, lastRecordCursor, orderByFields, columnTypes, nextParams, "after");
                    nextConditions.addAll(cursorConditions);

                    if (!nextConditions.isEmpty()) {
                        checkNextSql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, nextConditions));
                    }

                    Integer nextCount = namedParameterJdbcTemplate.queryForObject(checkNextSql.toString(), nextParams, Integer.class);
                    hasNextPage = nextCount != null && nextCount > 0;

                    // Check if there's a previous page by simulating a "before" cursor with the first record
                    Map<String, Object> firstRecordCursor = new HashMap<>();
                    for (String orderField : orderByFields.keySet()) {
                        firstRecordCursor.put(orderField, nodes.getFirst().get(orderField));
                    }

                    StringBuilder checkPrevSql = new StringBuilder(PostgresColumnTypeConstant.SELECT_COUNT_FROM_WITH_SPACE)
                            .append(getQualifiedTableName(tableName));

                    List<String> prevConditions = new ArrayList<>();
                    MapSqlParameterSource prevParams = new MapSqlParameterSource();

                    if (!arguments.isEmpty()) {
                        prevConditions.addAll(buildWhereConditions(arguments, prevParams, columnTypes));
                    }

                    List<String> cursorConditions2 = new ArrayList<>();
                    buildCursorConditions(cursorConditions2, firstRecordCursor, orderByFields, columnTypes, prevParams, "before");
                    prevConditions.addAll(cursorConditions2);

                    if (!prevConditions.isEmpty()) {
                        checkPrevSql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, prevConditions));
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
                Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
                TableInfo referencedTableInfo = tables.get(referencedTable);
                Set<String> availableColumns = referencedTableInfo != null
                        ? referencedTableInfo.getColumns().stream()
                        .map(ColumnInfo::getName)
                        .collect(Collectors.toSet())
                        : new HashSet<>();

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

                StringBuilder sql = new StringBuilder(PostgresColumnTypeConstant.SELECT_WITH_SPACE);
                sql.append(buildColumnList(requestedFields));
                sql.append(PostgresColumnTypeConstant.FROM_WITH_SPACE).append(getQualifiedTableName(referencedTable));
                sql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(quoteIdentifier(referencedColumn)).append(" = ?");
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
            
            Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
            TableInfo targetTableInfo = tables.get(targetTableName);
            Set<String> availableColumns = targetTableInfo != null
                    ? targetTableInfo.getColumns().stream()
                    .map(ColumnInfo::getName)
                    .collect(Collectors.toSet())
                    : new HashSet<>();

            // Filter requested fields to only include actual database columns (not nested relationships)
            List<String> requestedFields = environment.getSelectionSet().getFields().stream()
                    .map(SelectedField::getName)
                    .filter(availableColumns::contains) // Only include fields that exist as columns
                    .collect(Collectors.toList());

            if (requestedFields.isEmpty() && !availableColumns.isEmpty()) {
                requestedFields = new ArrayList<>(availableColumns);
            }

            StringBuilder sql = new StringBuilder(PostgresColumnTypeConstant.SELECT_WITH_SPACE);
            sql.append(buildColumnList(requestedFields));
            sql.append(PostgresColumnTypeConstant.FROM_WITH_SPACE).append(getQualifiedTableName(targetTableName));
            sql.append(PostgresColumnTypeConstant.WHERE_WITH_SPACE).append(quoteIdentifier(foreignKeyColumn)).append(" = ?");
            
            try {
                List<Map<String, Object>> results = jdbcTemplate.queryForList(sql.toString(), referencedValue);
                
                // Convert PostgreSQL arrays to Java Lists for GraphQL compatibility
                if (targetTableInfo != null) {
                    results = convertPostgresTypesToGraphQLTypes(results, targetTableInfo);
                }
                
                return results;
            } catch (Exception e) {
                log.error("Error fetching reverse relationship from {} to {}: {}", sourceTableName, targetTableName, e.getMessage());
                throw new DataFetcherException("Error fetching reverse relationship from " + sourceTableName + " to " + targetTableName + ": " + e.getMessage(), e);
            }
        };
    }

    /**
     * Gets the schema reflector for the current database type.
     * This method uses the service lookup to find the appropriate reflector implementation.
     *
     * @return The IDatabaseSchemaReflector instance for the current database type
     */
    private IDatabaseSchemaReflector getSchemaReflector() {
        if (schemaReflector != null) {
            return schemaReflector;
        }
        schemaReflector = serviceLookup.forBean(IDatabaseSchemaReflector.class, appConfig.getDatabaseType().getName());
        return schemaReflector;
    }
    
    /**
     * Checks if the given type is a custom enum type by looking it up in the schema reflector
     */
    private boolean isCustomEnumType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        
        try {
            return getSchemaReflector().getCustomEnumTypes().stream()
                    .anyMatch(enumType -> enumType.getName().equalsIgnoreCase(type));
        } catch (Exception e) {
            log.debug("Error checking custom enum types: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Checks if the given type is a custom composite type by looking it up in the schema reflector
     */
    private boolean isCustomCompositeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        
        try {
            return getSchemaReflector().getCustomCompositeTypes().stream()
                    .anyMatch(compositeType -> compositeType.getName().equalsIgnoreCase(type));
        } catch (Exception e) {
            log.debug("Error checking custom composite types: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Helper method to safely quote a SQL identifier
     *
     * @param identifier The raw identifier
     * @return The quoted identifier
     */
    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Helper method to build a fully-qualified, safely quoted table name
     *
     * @param tableName The table name
     * @return The fully qualified, quoted table name
     */
    private String getQualifiedTableName(String tableName) {
        return appConfig.getAllowedSchema() + "." + quoteIdentifier(tableName);
    }

    /**
     * Helper method to build a CSV list of quoted column names
     *
     * @param columns The list of column names
     * @return A comma-separated list of quoted column names, or "*" if empty
     */
    private String buildColumnList(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        return columns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
    }

    /**
     * Preloads relationship data to avoid N+1 queries.
     * This method fetches all related records in bulk and stores them in the GraphQL context.
     */
    private void preloadRelationships(
            DataFetchingEnvironment environment,
            String tableName,
            Set<String> relationshipFields,
            List<Map<String, Object>> results) {

        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);

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

                    // Get the table info for the referenced table to know which fields are actual columns
                    TableInfo referencedTableInfo = tables.get(referencedTable);
                    Set<String> availableColumns = referencedTableInfo != null
                            ? referencedTableInfo.getColumns().stream()
                            .map(ColumnInfo::getName)
                            .collect(Collectors.toSet())
                            : new HashSet<>();

                    // Filter requested fields to only include actual database columns (not nested relationships)
                    Set<String> requestedFields = selectedFields.stream()
                            .map(SelectedField::getName)
                            .filter(availableColumns::contains) // Only include fields that exist as columns
                            .collect(Collectors.toSet());

                    if (!requestedFields.isEmpty()) {
                        // Always include the reference column for proper mapping
                        requestedFields.add(referencedColumn);

                        // Build and execute batch query
                        String sql = PostgresColumnTypeConstant.SELECT_WITH_SPACE + buildColumnList(new ArrayList<>(requestedFields)) +
                                PostgresColumnTypeConstant.FROM_WITH_SPACE + getQualifiedTableName(referencedTable) +
                                PostgresColumnTypeConstant.WHERE_WITH_SPACE + quoteIdentifier(referencedColumn) + PostgresColumnTypeConstant.IN_WITH_SPACE + "(:ids)";

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

    /**
     * Builds cursor-based WHERE conditions for pagination.
     * This method creates conditions that properly handle multi-field ordering for cursor pagination.
     *
     * @param cursorConditions The list to add conditions to
     * @param cursorValues     The cursor field values
     * @param orderByFields    The ordering fields and directions
     * @param columnTypes      The column types for proper casting
     * @param paramSource      The parameter source for SQL parameters
     * @param direction        Either "after" or "before" to determine comparison direction
     */
    private void buildCursorConditions(
            List<String> cursorConditions,
            Map<String, Object> cursorValues,
            Map<String, String> orderByFields,
            Map<String, String> columnTypes,
            MapSqlParameterSource paramSource,
            String direction) {

        List<String> orderFields = new ArrayList<>(orderByFields.keySet());

        // Build a complex condition that handles multi-field ordering
        // For example, with fields [created_date, id] and direction "after":
        // (created_date > cursor_created_date) OR
        // (created_date = cursor_created_date AND id > cursor_id)

        List<String> orConditions = new ArrayList<>();

        for (int i = 0; i < orderFields.size(); i++) {
            List<String> andConditions = new ArrayList<>();

            for (int j = 0; j < i; j++) {
                String field = orderFields.get(j);
                String paramName = "cursor_" + field + "_" + direction;
                andConditions.add(quoteIdentifier(field) + " = :" + paramName);
                addTypedParameter(paramSource, paramName, cursorValues.get(field), columnTypes.get(field));
            }

            String currentField = orderFields.get(i);
            String currentDirection = orderByFields.get(currentField);
            String paramName = "cursor_" + currentField + "_" + direction;

            String operator;
            if (FieldConstant.AFTER.equals(direction)) {
                operator = "ASC".equalsIgnoreCase(currentDirection) ? ">" : "<";
            } else {
                operator = "ASC".equalsIgnoreCase(currentDirection) ? "<" : ">";
            }

            andConditions.add(quoteIdentifier(currentField) + " " + operator + " :" + paramName);
            addTypedParameter(paramSource, paramName, cursorValues.get(currentField), columnTypes.get(currentField));
            if (andConditions.size() == 1) {
                orConditions.add(andConditions.getFirst());
            } else {
                orConditions.add("(" + String.join(PostgresColumnTypeConstant.AND_WITH_SPACE, andConditions) + ")");
            }
        }

        if (!orConditions.isEmpty()) {
            if (orConditions.size() == 1) {
                cursorConditions.add(orConditions.getFirst());
            } else {
                cursorConditions.add("(" + String.join(" OR ", orConditions) + ")");
            }
        }
    }



    /**
     * Gets the column types for a table from the schema reflector.
     *
     * @param tableName The name of the table
     * @return A map of column names to their database types
     */
    private Map<String, String> getColumnTypes(String tableName) {
        Map<String, String> columnTypes = new HashMap<>();

        // Get table info from schema reflector
        Map<String, TableInfo> tables = getSchemaReflector().reflectSchema();
        TableInfo tableInfo = tables.get(tableName);

        if (tableInfo != null) {
            for (ColumnInfo column : tableInfo.getColumns()) {
                columnTypes.put(column.getName(), column.getType().toLowerCase());
            }
        }

        return columnTypes;
    }

    /**
     * Builds WHERE conditions for SQL queries based on GraphQL filter arguments.
     * Now supports the new filter format: {where: {customer_id: {eq: 524}}} and OR conditions.
     *
     * @param arguments   The GraphQL filter arguments
     * @param paramSource The SQL parameter source to populate
     * @param columnTypes The types of columns in the table
     * @return A list of SQL WHERE conditions
     */
    private List<String> buildWhereConditions(Map<String, Object> arguments, MapSqlParameterSource paramSource,
                                              Map<String, String> columnTypes) {
        List<String> conditions = new ArrayList<>();
        
        // Handle the "where" argument (new filter format)
        if (arguments.containsKey("where")) {
            Map<String, Object> whereConditions = (Map<String, Object>) arguments.get("where");
            conditions.addAll(buildFilterConditions(whereConditions, paramSource, columnTypes, "where"));
        }
        
        // Handle the "or" argument (array of filter objects)
        if (arguments.containsKey("or")) {
            List<Map<String, Object>> orConditions = (List<Map<String, Object>>) arguments.get("or");
            if (!orConditions.isEmpty()) {
                List<String> orParts = new ArrayList<>();
                for (int i = 0; i < orConditions.size(); i++) {
                    Map<String, Object> orCondition = orConditions.get(i);
                    List<String> orSubConditions = buildFilterConditions(orCondition, paramSource, columnTypes, "or_" + i);
                    if (!orSubConditions.isEmpty()) {
                        if (orSubConditions.size() == 1) {
                            orParts.add(orSubConditions.get(0));
                        } else {
                            orParts.add("(" + String.join(" AND ", orSubConditions) + ")");
                        }
                    }
                }
                if (!orParts.isEmpty()) {
                    conditions.add("(" + String.join(" OR ", orParts) + ")");
                }
            }
        }
        
        // Handle legacy format for backward compatibility (direct column filters like customer_id_eq: 524)
        List<String> operators = List.of(
                FieldConstant.OPERATOR_CONTAINS,
                FieldConstant.OPERATOR_STARTS_WITH,
                FieldConstant.OPERATOR_ENDS_WITH,
                FieldConstant.OPERATOR_GT,
                FieldConstant.OPERATOR_GTE,
                FieldConstant.OPERATOR_LT,
                FieldConstant.OPERATOR_LTE,
                FieldConstant.OPERATOR_IS_NULL,
                FieldConstant.OPERATOR_IS_NOT_NULL
        );

        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Skip special arguments
            if (key.equals("where") || key.equals("or") || key.equals(FieldConstant.ORDER_BY) || 
                key.equals(FieldConstant.LIMIT) || key.equals(FieldConstant.OFFSET) || key.equals(FieldConstant.FIRST) || 
                key.equals(FieldConstant.LAST) || key.equals(FieldConstant.BEFORE) || key.equals(FieldConstant.AFTER)) {
                continue;
            }

            if (value == null) {
                String quotedKey = "\"" + key + "\"";
                conditions.add(quotedKey + " IS NULL");
                continue;
            }

            boolean isOperatorFilter = false;
            String fieldName = key;
            String operator = null;

            for (String op : operators) {
                if (key.endsWith("_" + op)) {
                    fieldName = key.substring(0, key.length() - op.length() - 1); // Remove _operator suffix
                    operator = op;
                    isOperatorFilter = true;
                    break;
                }
            }

            if (isOperatorFilter) {
                String quotedFieldName = "\"" + fieldName + "\"";
                // Get column type for all operator filters
                String fieldColumnType = columnTypes.getOrDefault(fieldName, "").toLowerCase();

                switch (operator) {
                    case FieldConstant.OPERATOR_CONTAINS:
                        // Handle JSON types differently in legacy format too
                        if (fieldColumnType.contains("json")) {
                            conditions.add(quotedFieldName + "::text LIKE :" + key);
                            paramSource.addValue(key, "%" + value + "%");
                        } else if (fieldColumnType.contains("xml")) {
                            conditions.add(quotedFieldName + "::text LIKE :" + key);
                            paramSource.addValue(key, "%" + value + "%");
                        } else if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr")) {
                            conditions.add(quotedFieldName + "::text ILIKE :" + key);
                            paramSource.addValue(key, "%" + value + "%");
                        } else {
                            conditions.add(quotedFieldName + " LIKE :" + key);
                            paramSource.addValue(key, "%" + value + "%");
                        }
                        break;
                    case FieldConstant.OPERATOR_STARTS_WITH:
                        if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr") || fieldColumnType.contains("xml")) {
                            conditions.add(quotedFieldName + "::text ILIKE :" + key);
                            paramSource.addValue(key, value + "%");
                        } else {
                            conditions.add(quotedFieldName + " LIKE :" + key);
                            paramSource.addValue(key, value + "%");
                        }
                        break;
                    case FieldConstant.OPERATOR_ENDS_WITH:
                        if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr") || fieldColumnType.contains("xml")) {
                            conditions.add(quotedFieldName + "::text ILIKE :" + key);
                            paramSource.addValue(key, "%" + value);
                        } else {
                            conditions.add(quotedFieldName + " LIKE :" + key);
                            paramSource.addValue(key, "%" + value);
                        }
                        break;
                    case FieldConstant.OPERATOR_GT:
                        if (fieldColumnType.contains(ColumnTypeConstant.INTERVAL)) {
                            conditions.add(quotedFieldName + " > :" + key + "::interval");
                            paramSource.addValue(key, value.toString());
                        } else if (fieldColumnType.contains("timestamp") || fieldColumnType.contains("time")) {
                            conditions.add(quotedFieldName + " > :" + key + "::" + fieldColumnType);
                            paramSource.addValue(key, value.toString());
                        } else {
                            conditions.add(quotedFieldName + " > :" + key);
                            paramSource.addValue(key, value);
                        }
                        break;
                    case FieldConstant.OPERATOR_GTE:
                        if (fieldColumnType.contains(ColumnTypeConstant.INTERVAL)) {
                            conditions.add(quotedFieldName + " >= :" + key + "::interval");
                            paramSource.addValue(key, value.toString());
                        } else if (fieldColumnType.contains("timestamp") || fieldColumnType.contains("time")) {
                            conditions.add(quotedFieldName + " >= :" + key + "::" + fieldColumnType);
                            paramSource.addValue(key, value.toString());
                        } else {
                            conditions.add(quotedFieldName + " >= :" + key);
                            paramSource.addValue(key, value);
                        }
                        break;
                    case FieldConstant.OPERATOR_LT:
                        if (fieldColumnType.contains(ColumnTypeConstant.INTERVAL)) {
                            conditions.add(quotedFieldName + " < :" + key + "::interval");
                            paramSource.addValue(key, value.toString());
                        } else if (fieldColumnType.contains("timestamp") || fieldColumnType.contains("time")) {
                            conditions.add(quotedFieldName + " < :" + key + "::" + fieldColumnType);
                            paramSource.addValue(key, value.toString());
                        } else {
                            conditions.add(quotedFieldName + " < :" + key);
                            paramSource.addValue(key, value);
                        }
                        break;
                    case FieldConstant.OPERATOR_LTE:
                        if (fieldColumnType.contains(ColumnTypeConstant.INTERVAL)) {
                            conditions.add(quotedFieldName + " <= :" + key + "::interval");
                            paramSource.addValue(key, value.toString());
                        } else if (fieldColumnType.contains("timestamp") || fieldColumnType.contains("time")) {
                            conditions.add(quotedFieldName + " <= :" + key + "::" + fieldColumnType);
                            paramSource.addValue(key, value.toString());
                        } else {
                            conditions.add(quotedFieldName + " <= :" + key);
                            paramSource.addValue(key, value);
                        }
                        break;
                    case FieldConstant.OPERATOR_IS_NULL:
                        // Ignore the actual value, just add the IS NULL condition
                        if (value instanceof Boolean && (Boolean) value) {
                            conditions.add(quotedFieldName + " IS NULL");
                        } else {
                            conditions.add(quotedFieldName + " IS NOT NULL");
                        }
                        break;
                    case FieldConstant.OPERATOR_IS_NOT_NULL:
                        // Ignore the actual value, just add the IS NOT NULL condition
                        if (value instanceof Boolean && (Boolean) value) {
                            conditions.add(quotedFieldName + " IS NOT NULL");
                        } else {
                            conditions.add(quotedFieldName + " IS NULL");
                        }
                        break;
                    default:
                        // Unknown operator, skip
                        break;
                }
            } else {
                // Basic equality condition (legacy format)
                String columnType = columnTypes.getOrDefault(key, "").toLowerCase();
                String quotedKey = "\"" + key + "\"";

                // Handle UUID type conversion
                if (columnType.contains(ColumnTypeConstant.UUID) && value instanceof String) {
                    try {
                        // Convert string to UUID
                        UUID uuid = UUID.fromString((String) value);
                        conditions.add(quotedKey + " = :" + key);
                        paramSource.addValue(key, uuid);
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid UUID format: {}", value);
                        throw new DataFetcherException("Invalid UUID format for column " + key + ": " + value, e);
                    }
                }
                // Handle interval types with proper casting
                else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
                    conditions.add(quotedKey + " = :" + key + "::interval");
                    paramSource.addValue(key, value.toString());
                }
                // Handle network types (inet, cidr, macaddr) with casting
                else if (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr")) {
                    conditions.add(quotedKey + " = :" + key + "::" + columnType);
                    paramSource.addValue(key, value.toString());
                }
                // Handle enhanced datetime types with casting
                else if (columnType.contains("timestamp") || columnType.contains("time")) {
                    conditions.add(quotedKey + " = :" + key + "::" + columnType);
                    paramSource.addValue(key, value.toString());
                }
                // Handle numeric type conversions
                else if (PostgresTypeOperator.isIntegerType(columnType) || PostgresTypeOperator.isFloatingPointType(columnType)) {

                    if (value instanceof String) {
                        try {
                            // Check if it's an integer type
                            if (PostgresTypeOperator.isIntegerType(columnType)) {
                                int numericValue = Integer.parseInt((String) value);
                                conditions.add(quotedKey + " = :" + key);
                                paramSource.addValue(key, numericValue);
                            }
                            // Check if it's a long type
                            else if (columnType.contains(ColumnTypeConstant.BIGINT)) {
                                long numericValue = Long.parseLong((String) value);
                                conditions.add(quotedKey + " = :" + key);
                                paramSource.addValue(key, numericValue);
                            }
                            // Handle floating point types
                            else {
                                double numericValue = Double.parseDouble((String) value);
                                conditions.add(quotedKey + " = :" + key);
                                paramSource.addValue(key, numericValue);
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Invalid numeric format for column {} : {}", key, value);
                            conditions.add(quotedKey + " = :" + key);
                            paramSource.addValue(key, value);
                        }
                    } else {
                        conditions.add(quotedKey + " = :" + key);
                        paramSource.addValue(key, value);
                    }
                }
                // Handle custom enum types with explicit casting
                else if (isCustomEnumType(columnType)) {
                    conditions.add(quotedKey + " = :" + key + "::" + columnType);
                    paramSource.addValue(key, value.toString());
                }
                else {
                    conditions.add(quotedKey + " = :" + key);
                    paramSource.addValue(key, value);
                }
            }
        }

        return conditions;
    }
    
    /**
     * Builds filter conditions from the new filter object format.
     * Handles filters like {customer_id: {eq: 524, gt: 100}}
     */
    private List<String> buildFilterConditions(Map<String, Object> filterObj, MapSqlParameterSource paramSource, 
                                               Map<String, String> columnTypes, String paramPrefix) {
        List<String> conditions = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : filterObj.entrySet()) {
            String columnName = entry.getKey();
            Object filterValue = entry.getValue();
            
            if (filterValue == null) {
                continue;
            }
            
            // filterValue should be a Map containing operators like {eq: 524, gt: 100}
            if (!(filterValue instanceof Map)) {
                continue;
            }
            
            Map<String, Object> operators = (Map<String, Object>) filterValue;
            String quotedColumnName = "\"" + columnName + "\"";
            
            for (Map.Entry<String, Object> opEntry : operators.entrySet()) {
                String operator = opEntry.getKey();
                Object value = opEntry.getValue();
                
                if (value == null && !operator.equals("isNull") && !operator.equals("isNotNull")) {
                    continue;
                }
                
                String paramName = paramPrefix + "_" + columnName + "_" + operator;
                
                String columnType = columnTypes.get(columnName);
                boolean isInterval = columnType != null && columnType.toLowerCase().contains(ColumnTypeConstant.INTERVAL);
                
                switch (operator.toLowerCase()) {
                    case "eq":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " = :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
                            conditions.add(quotedColumnName + " = :" + paramName + "::" + columnType);
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " = :" + paramName + "::" + columnType);
                        } else if (columnType != null && columnType.contains("xml")) {
                            conditions.add(quotedColumnName + "::text = :" + paramName);
                        } else if (columnType != null && isCustomEnumType(columnType)) {
                            conditions.add(quotedColumnName + " = :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " = :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "neq":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " != :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
                            conditions.add(quotedColumnName + " != :" + paramName + "::" + columnType);
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " != :" + paramName + "::" + columnType);
                        } else if (columnType != null && columnType.contains("xml")) {
                            conditions.add(quotedColumnName + "::text != :" + paramName);
                        } else if (columnType != null && isCustomEnumType(columnType)) {
                            conditions.add(quotedColumnName + " != :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " != :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "gt":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " > :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " > :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " > :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "gte":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " >= :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " >= :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " >= :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "lt":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " < :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " < :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " < :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "lte":
                        if (isInterval) {
                            conditions.add(quotedColumnName + " <= :" + paramName + "::interval");
                        } else if (columnType != null && (columnType.contains("timestamp") || columnType.contains("time"))) {
                            conditions.add(quotedColumnName + " <= :" + paramName + "::" + columnType);
                        } else {
                            conditions.add(quotedColumnName + " <= :" + paramName);
                        }
                        addTypedParameter(paramSource, paramName, value, columnType);
                        break;
                        
                    case "like":
                        conditions.add(quotedColumnName + " LIKE :" + paramName);
                        paramSource.addValue(paramName, "%" + value + "%");
                        break;
                        
                    case "ilike":
                        conditions.add(quotedColumnName + " ILIKE :" + paramName);
                        paramSource.addValue(paramName, "%" + value + "%");
                        break;
                        
                    case "contains":
                        // Handle JSON types differently
                        if (columnType != null && (columnType.toLowerCase().contains("json"))) {
                            // For JSON/JSONB, use PostgreSQL text search on the JSON representation
                            conditions.add(quotedColumnName + "::text LIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value + "%");
                        } else if (columnType != null && columnType.toLowerCase().contains("xml")) {
                            // For XML, cast to text for LIKE operations
                            conditions.add(quotedColumnName + "::text LIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value + "%");
                        } else if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
                            // For network types, cast to text for case-insensitive LIKE operations
                            conditions.add(quotedColumnName + "::text ILIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value + "%");
                        } else {
                            // Standard string LIKE operation
                            conditions.add(quotedColumnName + " LIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value + "%");
                        }
                        break;
                        
                    case "startswith":
                        if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
                            // Network types need to be cast to text for LIKE operations  
                            conditions.add(quotedColumnName + "::text ILIKE :" + paramName);
                            paramSource.addValue(paramName, value + "%");
                        } else {
                            // Standard string LIKE operation
                            conditions.add(quotedColumnName + " LIKE :" + paramName);
                            paramSource.addValue(paramName, value + "%");
                        }
                        break;
                        
                    case "endswith":
                        if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
                            // Network types need to be cast to text for LIKE operations
                            conditions.add(quotedColumnName + "::text ILIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value);
                        } else {
                            // Standard string LIKE operation
                            conditions.add(quotedColumnName + " LIKE :" + paramName);
                            paramSource.addValue(paramName, "%" + value);
                        }
                        break;
                        
                    case "isnull":
                        if (value instanceof Boolean && (Boolean) value) {
                            conditions.add(quotedColumnName + " IS NULL");
                        } else {
                            conditions.add(quotedColumnName + " IS NOT NULL");
                        }
                        break;
                        
                    case "isnotnull":
                        if (value instanceof Boolean && (Boolean) value) {
                            conditions.add(quotedColumnName + " IS NOT NULL");
                        } else {
                            conditions.add(quotedColumnName + " IS NULL");
                        }
                        break;
                        
                    case "in":
                        if (value instanceof List) {
                            List<?> valueList = (List<?>) value;
                            if (!valueList.isEmpty()) {
                                if (isInterval) {
                                    // For intervals, we need to cast each element individually
                                    StringBuilder inClause = new StringBuilder("(");
                                    for (int i = 0; i < valueList.size(); i++) {
                                        if (i > 0) inClause.append(", ");
                                        String itemParamName = paramName + "_" + i;
                                        inClause.append(":").append(itemParamName).append("::interval");
                                        paramSource.addValue(itemParamName, valueList.get(i).toString());
                                    }
                                    inClause.append(")");
                                    conditions.add(quotedColumnName + " IN " + inClause.toString());
                                } else {
                                    conditions.add(quotedColumnName + " IN (:" + paramName + ")");
                                    addTypedParameter(paramSource, paramName, valueList, columnType);
                                }
                            }
                        }
                        break;
                        
                    case "notin":
                        if (value instanceof List) {
                            List<?> valueList = (List<?>) value;
                            if (!valueList.isEmpty()) {
                                if (isInterval) {
                                    // For intervals, we need to cast each element individually
                                    StringBuilder notInClause = new StringBuilder("(");
                                    for (int i = 0; i < valueList.size(); i++) {
                                        if (i > 0) notInClause.append(", ");
                                        String itemParamName = paramName + "_" + i;
                                        notInClause.append(":").append(itemParamName).append("::interval");
                                        paramSource.addValue(itemParamName, valueList.get(i).toString());
                                    }
                                    notInClause.append(")");
                                    conditions.add(quotedColumnName + " NOT IN " + notInClause.toString());
                                } else {
                                    conditions.add(quotedColumnName + " NOT IN (:" + paramName + ")");
                                    addTypedParameter(paramSource, paramName, valueList, columnType);
                                }
                            }
                        }
                        break;
                        
                    default:
                        // Unknown operator, skip
                        break;
                }
            }
        }
        
        return conditions;
    }

    /**
     * Adds a parameter to the parameter source with proper type conversion.
     * Includes comprehensive date/timestamp handling.
     */
    private void addTypedParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
        if (value == null || value.toString().isEmpty()) {
            paramSource.addValue(paramName, null);
            return;
        }
        
        String type = columnType != null ? columnType.toLowerCase() : "";
        
        try {
            // Handle arrays for IN/NOT IN operations
            if (value instanceof List<?>) {
                List<?> listValue = (List<?>) value;
                if (listValue.isEmpty()) {
                    paramSource.addValue(paramName, listValue);
                    return;
                }
                
                List<Object> convertedList = new ArrayList<>();
                for (Object item : listValue) {
                    if (item == null) {
                        convertedList.add(null);
                    } else {
                        String itemStr = item.toString();
                        if (type.contains(ColumnTypeConstant.UUID)) {
                            convertedList.add(UUID.fromString(itemStr));
                        } else if (PostgresTypeOperator.isIntegerType(type)) {
                            convertedList.add(Integer.parseInt(itemStr));
                        } else if (PostgresTypeOperator.isFloatingPointType(type)) {
                            convertedList.add(Double.parseDouble(itemStr));
                        } else if (PostgresTypeOperator.isBooleanType(type)) {
                            convertedList.add(Boolean.parseBoolean(itemStr));
                        } else if (type.contains(ColumnTypeConstant.INTERVAL)) {
                            // Intervals should be passed as strings - PostgreSQL will handle the conversion
                            convertedList.add(itemStr);
                        } else if (PostgresTypeOperator.isDateTimeType(type) && !type.contains(ColumnTypeConstant.INTERVAL)) {
                            // Handle date/timestamp conversion for arrays (excluding intervals)
                            Object convertedDate = convertToDateTime(itemStr, type);
                            convertedList.add(convertedDate);
                        } else {
                            convertedList.add(itemStr);
                        }
                    }
                }
                paramSource.addValue(paramName, convertedList);
                return;
            }
            
            // Handle single values
            String valueStr = value.toString();
            
            if (type.contains(ColumnTypeConstant.UUID)) {
                paramSource.addValue(paramName, UUID.fromString(valueStr));
            } else if (PostgresTypeOperator.isIntegerType(type)) {
                paramSource.addValue(paramName, Integer.parseInt(valueStr));
            } else if (PostgresTypeOperator.isFloatingPointType(type)) {
                paramSource.addValue(paramName, Double.parseDouble(valueStr));
            } else if (PostgresTypeOperator.isBooleanType(type)) {
                paramSource.addValue(paramName, Boolean.parseBoolean(valueStr));
            } else if (type.contains(ColumnTypeConstant.INTERVAL)) {
                // Intervals are durations, not timestamps - pass as string for PostgreSQL to handle
                paramSource.addValue(paramName, valueStr);
            } else if (PostgresTypeOperator.isDateTimeType(type) && !type.contains(ColumnTypeConstant.INTERVAL)) {
                // Handle date/timestamp conversion with multiple formats (excluding intervals)
                Object convertedDate = convertToDateTime(valueStr, type);
                paramSource.addValue(paramName, convertedDate);
            } else {
                // Default to string
                paramSource.addValue(paramName, valueStr);
            }
        } catch (Exception e) {
            log.warn("Error converting value for {} : {} ", paramName, e.getMessage());
            // Fallback to string
            String valueStr = value.toString();
            paramSource.addValue(paramName, valueStr);
        }
    }
    
    /**
     * Converts a string value to appropriate date/timestamp type based on column type
     */
    private Object convertToDateTime(String valueStr, String columnType) {
        // Try multiple date/time formats
        String[] dateFormats = {
            "yyyy-MM-dd",           // 2006-02-14
            "yyyy-MM-dd HH:mm:ss",  // 2013-05-26 14:49:45
            "yyyy-MM-dd HH:mm:ss.SSS", // 2013-05-26 14:49:45.738
            "yyyy-MM-dd'T'HH:mm:ss",    // ISO format
            "yyyy-MM-dd'T'HH:mm:ss.SSS" // ISO format with milliseconds
        };
        
        String type = columnType.toLowerCase();
        
        // For date columns, try to parse as LocalDate first
        if (type.contains(ColumnTypeConstant.DATE) && !type.contains(ColumnTypeConstant.TIMESTAMP)) {
            try {
                LocalDate localDate = LocalDate.parse(valueStr, DateTimeFormatter.ISO_DATE);
                return Date.valueOf(localDate);
            } catch (DateTimeParseException e) {
                // If ISO format fails, try other formats
                for (String format : dateFormats) {
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                        if (format.contains("HH")) {
                            // Contains time, parse as datetime then extract date
                            LocalDateTime dateTime = LocalDateTime.parse(valueStr, formatter);
                            return Date.valueOf(dateTime.toLocalDate());
                        } else {
                            // Date only
                            LocalDate localDate = LocalDate.parse(valueStr, formatter);
                            return Date.valueOf(localDate);
                        }
                    } catch (DateTimeParseException ignored) {
                        // Try next format
                    }
                }
            }
        }
        
        // For timestamp columns, try to parse as LocalDateTime
        if (type.contains(ColumnTypeConstant.TIMESTAMP)) {
            // Try parsing with different formats
            for (String format : dateFormats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                    if (format.contains("HH")) {
                        // Contains time
                        LocalDateTime dateTime = LocalDateTime.parse(valueStr, formatter);
                        return Timestamp.valueOf(dateTime);
                    } else {
                        // Date only, assume start of day
                        LocalDate localDate = LocalDate.parse(valueStr, formatter);
                        return Timestamp.valueOf(localDate.atStartOfDay());
                    }
                } catch (DateTimeParseException ignored) {
                    // Try next format
                }
            }
            
            // Try ISO formats
            try {
                LocalDateTime dateTime = LocalDateTime.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return Timestamp.valueOf(dateTime);
            } catch (DateTimeParseException e) {
                try {
                    LocalDate localDate = LocalDate.parse(valueStr, DateTimeFormatter.ISO_LOCAL_DATE);
                    return Timestamp.valueOf(localDate.atStartOfDay());
                } catch (DateTimeParseException ignored) {
                    // Fall through to string fallback
                }
            }
        }
        
        // If all parsing fails, return as string and let PostgreSQL handle it
        log.warn("Could not parse date/time value: {} for column type: {}, using string fallback", valueStr, columnType);
        return valueStr;
    }

    /**
     * Converts PostgreSQL array columns and custom types to Java objects for GraphQL compatibility
     */
    private List<Map<String, Object>> convertPostgresTypesToGraphQLTypes(List<Map<String, Object>> results, TableInfo tableInfo) {
        // Get array column information
        Map<String, String> arrayColumns = tableInfo.getColumns().stream()
                .filter(col -> PostgresTypeOperator.isArrayType(col.getType()))
                .collect(Collectors.toMap(ColumnInfo::getName, ColumnInfo::getType));
        
        // Get custom type columns (will determine enum vs composite based on actual data)
        Map<String, String> customTypeColumns = tableInfo.getColumns().stream()
                .filter(col -> isCustomEnumType(col.getType()) || 
                              isCustomCompositeType(col.getType()))
                .filter(col -> !PostgresTypeOperator.isArrayType(col.getType())) // Exclude arrays for now
                .collect(Collectors.toMap(ColumnInfo::getName, ColumnInfo::getType));
        
        if (arrayColumns.isEmpty() && customTypeColumns.isEmpty()) {
            return results; // No special columns, return as-is
        }
        
        return results.stream().map(row -> {
            Map<String, Object> convertedRow = new HashMap<>(row);
            
            // Process array columns
            for (Map.Entry<String, String> arrayCol : arrayColumns.entrySet()) {
                String columnName = arrayCol.getKey();
                String columnType = arrayCol.getValue();
                Object value = row.get(columnName);
                
                if (value != null) {
                    List<Object> convertedArray = convertPostgresArrayToList(value, columnType);
                    convertedRow.put(columnName, convertedArray);
                }
            }
            
            // Process custom type columns (determine enum vs composite based on data format)
            for (Map.Entry<String, String> customCol : customTypeColumns.entrySet()) {
                String columnName = customCol.getKey();
                String columnType = customCol.getValue();
                Object value = row.get(columnName);
                
                if (value != null) {
                    String valueStr = value.toString();
                    
                    // If value starts with '(' and ends with ')', it's likely a composite type
                    if (valueStr.startsWith("(") && valueStr.endsWith(")")) {
                        // Process as composite type
                        Map<String, Object> convertedComposite = convertPostgresCompositeToMap(value, columnType);
                        convertedRow.put(columnName, convertedComposite);
                    } else {
                        // Process as enum type (simple string value)
                        convertedRow.put(columnName, valueStr);
                    }
                }
            }
            
            return convertedRow;
        }).collect(Collectors.toList());
    }
    
    /**
     * Converts a PostgreSQL array value to a Java List
     */
    private List<Object> convertPostgresArrayToList(Object arrayValue, String columnType) {
        try {
            // Handle java.sql.Array objects
            if (arrayValue instanceof Array) {
                Array sqlArray = (Array) arrayValue;
                try {
                    Object[] elements = (Object[]) sqlArray.getArray();
                    return Arrays.stream(elements)
                            .map(element -> convertArrayElement(element, columnType))
                            .collect(Collectors.toList());
                } catch (Exception e) {
                    // If connection is closed, fall back to string representation
                    log.debug("SQL Array access failed (likely connection closed), trying string representation: {}", e.getMessage());
                    return parsePostgresArrayString(arrayValue.toString(), columnType);
                }
            }
            
            // Handle PostgreSQL string representation like "{1,2,3}" or "{apple,banana,cherry}"
            if (arrayValue instanceof String) {
                String arrayStr = (String) arrayValue;
                return parsePostgresArrayString(arrayStr, columnType);
            }
            
            log.warn("Unexpected array value type: {} for column type: {}", arrayValue.getClass(), columnType);
            return List.of(); // Return empty list as fallback
            
        } catch (Exception e) {
            log.error("Error converting PostgreSQL array to List for column type: {}", columnType, e);
            return List.of(); // Return empty list on error
        }
    }
    
    /**
     * Converts individual array elements, handling both custom and regular types appropriately
     */
    private Object convertArrayElement(Object element, String columnType) {
        if (element == null) {
            return null;
        }
        
        // Extract base type from array type (e.g., "test_priority[]" -> "test_priority")
        String baseType = columnType.replace("[]", "");
        
        // Check if it's a custom enum type
        if (isCustomEnumType(baseType)) {
            return element.toString(); // Custom enums are returned as strings
        }
        
        // Check if it's a custom composite type
        if (isCustomCompositeType(baseType)) {
            String elementStr = element.toString();
            if (elementStr.startsWith("(") && elementStr.endsWith(")")) {
                return convertPostgresCompositeToMap(element, baseType);
            }
        }
        
        // Handle regular PostgreSQL types
        String elementStr = element.toString();
        String type = baseType.toLowerCase();
        
        try {
            if (PostgresTypeOperator.isIntegerType(type)) {
                return Integer.parseInt(elementStr);
            } else if (PostgresTypeOperator.isFloatingPointType(type)) {
                return Double.parseDouble(elementStr);
            } else if (PostgresTypeOperator.isBooleanType(type)) {
                return Boolean.parseBoolean(elementStr);
            } else if (type.contains(ColumnTypeConstant.UUID)) {
                return elementStr; // Keep UUIDs as strings in GraphQL
            } else {
                return elementStr; // Default to string
            }
        } catch (NumberFormatException e) {
            log.warn("Could not convert array element '{}' to type '{}', using string", elementStr, type);
            return elementStr;
        }
    }
    
    /**
     * Parses PostgreSQL array string representation like "{1,2,3}" into a Java List
     */
    private List<Object> parsePostgresArrayString(String arrayStr, String columnType) {
        if (arrayStr == null || arrayStr.trim().isEmpty()) {
            return List.of();
        }
        
        // Remove curly braces and split by comma
        String trimmed = arrayStr.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        
        if (trimmed.isEmpty()) {
            return List.of(); // Empty array
        }
        
        // Handle quoted string arrays vs unquoted numeric arrays
        List<String> elements;
        if (trimmed.contains("\"")) {
            // String array with quotes: {"apple","banana","cherry"}
            elements = parseQuotedArrayElements(trimmed);
        } else {
            // Simple comma-separated values: 1,2,3,4,5
            elements = Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        
        // Convert elements to appropriate types based on column type
        return elements.stream()
                .map(element -> convertArrayElement(element, columnType))
                .collect(Collectors.toList());
    }
    
    /**
     * Parses quoted array elements handling escaped quotes
     */
    private List<String> parseQuotedArrayElements(String arrayContent) {
        List<String> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        
        for (char c : arrayContent.toCharArray()) {
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                if (current.length() > 0) {
                    elements.add(current.toString().trim());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            elements.add(current.toString().trim());
        }
        
        return elements;
    }
    
    /**
     * Converts a PostgreSQL composite type to a Map for GraphQL compatibility
     */
    private Map<String, Object> convertPostgresCompositeToMap(Object compositeValue, String columnType) {
        if (compositeValue == null) {
            return null;
        }

        try {
            // PostgreSQL composite types are typically returned as string representations
            String compositeStr = compositeValue.toString();
            
            if (compositeStr == null || compositeStr.trim().isEmpty()) {
                return Map.of();
            }
            
            return parsePostgresCompositeString(compositeStr, columnType);
            
        } catch (Exception e) {
            log.error("Error converting PostgreSQL composite to Map for column type: {}", columnType, e);
            return Map.of(); // Return empty map on error
        }
    }

    /**
     * Parses PostgreSQL composite string representation like "(40.7589,-73.9851,New York)" into a Map
     */
    private Map<String, Object> parsePostgresCompositeString(String compositeStr, String columnType) {
        if (compositeStr == null || compositeStr.trim().isEmpty()) {
            return Map.of();
        }
        
        log.debug("Parsing composite string: '{}' for type: '{}'", compositeStr, columnType);

        // Remove outer parentheses: "(40.7589,-73.9851,New York)" -> "40.7589,-73.9851,New York"
        String content = compositeStr.trim();
        if (content.startsWith("(") && content.endsWith(")")) {
            content = content.substring(1, content.length() - 1);
        }

        // Split by commas - this is a simple implementation that may need enhancement for complex cases
        String[] parts = content.split(",");
        log.debug("Split into {} parts: {}", parts.length, Arrays.toString(parts));
        
        // Get composite type metadata to use proper field names
        List<String> fieldNames = getCompositeTypeFieldNames(columnType);
        log.debug("Field names for type '{}': {}", columnType, fieldNames);
        
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            
            // Remove quotes if present
            if (part.startsWith("\"") && part.endsWith("\"")) {
                part = part.substring(1, part.length() - 1);
            }
            
            // Try to convert to appropriate type
            Object value = convertCompositeAttributeValue(part);
            
            // Use actual field name if available, otherwise fall back to generic name
            String fieldName = (i < fieldNames.size()) ? fieldNames.get(i) : "attr_" + i;
            result.put(fieldName, value);
            log.debug("Mapped field '{}' = '{}'", fieldName, value);
        }
        
        log.debug("Final parsed result: {}", result);
        return result;
    }
    
    /**
     * Gets the field names for a composite type from the schema reflector
     */
    private List<String> getCompositeTypeFieldNames(String compositeTypeName) {
        try {
            List<CustomCompositeTypeInfo> compositeTypes = getSchemaReflector().getCustomCompositeTypes();
            
            for (CustomCompositeTypeInfo compositeType : compositeTypes) {
                if (compositeType.getName().equalsIgnoreCase(compositeTypeName)) {
                    return compositeType.getAttributes().stream()
                            .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                            .map(CompositeTypeAttribute::getName)
                            .collect(Collectors.toList());
                }
            }
            
            // Fallback: provide hardcoded field names for known composite types
            if ("address".equalsIgnoreCase(compositeTypeName)) {
                return Arrays.asList("street", "city", "state", "postal_code", "country");
            }
            if ("contact_info".equalsIgnoreCase(compositeTypeName)) {
                return Arrays.asList("email", "phone", "website");
            }
            if ("product_dimensions".equalsIgnoreCase(compositeTypeName)) {
                return Arrays.asList("length", "width", "height", "weight", "units");
            }
            
            log.warn("Composite type '{}' not found in schema, using generic field names", compositeTypeName);
            return new ArrayList<>();
            
        } catch (Exception e) {
            log.error("Error getting composite type field names for type: {}", compositeTypeName, e);
            return new ArrayList<>();
        }
    }

    /**
     * Converts a composite attribute value to the appropriate Java type
     */
    private Object convertCompositeAttributeValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        value = value.trim();

        // Try to parse as number first
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            } else {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            // Not a number, return as string
            return value;
        }
    }
}
