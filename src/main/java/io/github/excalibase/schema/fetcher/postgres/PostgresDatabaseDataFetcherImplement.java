package io.github.excalibase.schema.fetcher.postgres;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.constant.FieldConstant;
import io.github.excalibase.constant.SQLSyntax;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.DataFetcherException;
import io.github.excalibase.model.ColumnInfo;
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

import java.util.ArrayList;
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

    /**
     * Creates a DataFetcher for fetching data from a specific table.
     * This method constructs the SQL query based on the provided table name and handles pagination and filtering.
     *
     * @param tableName The name of the table to fetch data from
     * @return A DataFetcher that retrieves data from the specified table
     */
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

            StringBuilder sql = new StringBuilder(SQLSyntax.SELECT_WITH_SPACE);
            sql.append(buildColumnList(requestedFields));
            sql.append(SQLSyntax.FROM_WITH_SPACE).append(getQualifiedTableName(tableName));

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
                    sql.append(SQLSyntax.WHERE_WITH_SPACE).append(String.join(SQLSyntax.AND_WITH_SPACE, conditions));
                }
            }

            if (!orderByFields.isEmpty()) {
                sql.append(SQLSyntax.ORDER_BY_WITH_SPACE);
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
                sql.append(SQLSyntax.LIMIT_WITH_SPACE + " :limit");
                paramSource.addValue(FieldConstant.LIMIT, limit);
            }

            if (offset != null) {
                sql.append(SQLSyntax.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }

            List<Map<String, Object>> results = namedParameterJdbcTemplate.queryForList(sql.toString(), paramSource);
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
                    .flatMap(field -> field.getSelectionSet().getFields().stream())
                    .filter(field -> field.getName().equals(FieldConstant.NODE))
                    .flatMap(field -> field.getSelectionSet().getFields().stream())
                    .filter(field -> !availableColumns.contains(field.getName()))
                    .map(SelectedField::getName)
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

            StringBuilder countSql = new StringBuilder(SQLSyntax.SELECT_COUNT_FROM_WITH_SPACE)
                    .append(getQualifiedTableName(tableName));

            if (!arguments.isEmpty()) {
                List<String> conditions = buildWhereConditions(arguments, paramSource, columnTypes);

                if (!conditions.isEmpty()) {
                    countSql.append(SQLSyntax.WHERE_WITH_SPACE).append(String.join(SQLSyntax.AND_WITH_SPACE, conditions));
                }
            }

            Integer totalCount = namedParameterJdbcTemplate.queryForObject(countSql.toString(), paramSource, Integer.class);

            StringBuilder dataSql = new StringBuilder(SQLSyntax.SELECT_WITH_SPACE);
            dataSql.append(buildColumnList(availableColumns));
            dataSql.append(SQLSyntax.FROM_WITH_SPACE).append(getQualifiedTableName(tableName));

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
                dataSql.append(SQLSyntax.WHERE_WITH_SPACE).append(String.join(SQLSyntax.AND_WITH_SPACE, conditions));
            }

            // Add ORDER BY based on orderBy parameter or default ordering
            if (!orderByFields.isEmpty()) {
                dataSql.append(SQLSyntax.ORDER_BY_WITH_SPACE);
                List<String> orderClauses = new ArrayList<>();

                for (Map.Entry<String, String> entry : orderByFields.entrySet()) {
                    if (availableColumns.contains(entry.getKey())) {
                        orderClauses.add(quoteIdentifier(entry.getKey()) + " " + entry.getValue());
                    }
                }

                dataSql.append(String.join(", ", orderClauses));
            }

            Integer limit = first != null ? first : (last != null ? last : 10); // Default to 10 if neither specified
            dataSql.append(SQLSyntax.LIMIT_WITH_SPACE + ":limit");
            paramSource.addValue(FieldConstant.LIMIT, limit);

            // Add OFFSET for offset-based pagination
            if (useOffsetPagination) {
                dataSql.append(SQLSyntax.OFFSET_WITH_SPACE + ":offset");
                paramSource.addValue(FieldConstant.OFFSET, offset);
            }
            List<Map<String, Object>> nodes = namedParameterJdbcTemplate.queryForList(dataSql.toString(), paramSource);

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

                    StringBuilder checkNextSql = new StringBuilder(SQLSyntax.SELECT_COUNT_FROM_WITH_SPACE)
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
                        checkNextSql.append(SQLSyntax.WHERE_WITH_SPACE).append(String.join(SQLSyntax.AND_WITH_SPACE, nextConditions));
                    }

                    Integer nextCount = namedParameterJdbcTemplate.queryForObject(checkNextSql.toString(), nextParams, Integer.class);
                    hasNextPage = nextCount != null && nextCount > 0;

                    // Check if there's a previous page by simulating a "before" cursor with the first record
                    Map<String, Object> firstRecordCursor = new HashMap<>();
                    for (String orderField : orderByFields.keySet()) {
                        firstRecordCursor.put(orderField, nodes.getFirst().get(orderField));
                    }

                    StringBuilder checkPrevSql = new StringBuilder(SQLSyntax.SELECT_COUNT_FROM_WITH_SPACE)
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
                        checkPrevSql.append(SQLSyntax.WHERE_WITH_SPACE).append(String.join(SQLSyntax.AND_WITH_SPACE, prevConditions));
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

    /**
     * Creates a DataFetcher for relationship fields that checks the batch context first.
     * This handles fetching the related record(s) for a given foreign key efficiently.
     *
     * @param tableName        The name of the table containing the foreign key
     * @param foreignKeyColumn The name of the foreign key column
     * @param referencedTable  The name of the referenced table
     * @param referencedColumn The name of the referenced column
     * @return A DataFetcher that fetches the related record
     */
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

                StringBuilder sql = new StringBuilder(SQLSyntax.SELECT_WITH_SPACE);
                sql.append(buildColumnList(requestedFields));
                sql.append(SQLSyntax.FROM_WITH_SPACE).append(getQualifiedTableName(referencedTable));
                sql.append(SQLSyntax.WHERE_WITH_SPACE).append(quoteIdentifier(referencedColumn)).append(" = ?");
                try {
                    return jdbcTemplate.queryForMap(sql.toString(), foreignKeyValue);
                } catch (Exception e) {
                    log.error("Error fetching relationship: {}", e.getMessage());
                    throw new DataFetcherException("Error fetching relationship for " + referencedTable + ": " + e.getMessage(), e);
                }
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
                            .map(field -> field.getSelectionSet().getFields())
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
                        String sql = SQLSyntax.SELECT_WITH_SPACE + buildColumnList(new ArrayList<>(requestedFields)) +
                                SQLSyntax.FROM_WITH_SPACE + getQualifiedTableName(referencedTable) +
                                SQLSyntax.WHERE_WITH_SPACE + quoteIdentifier(referencedColumn) + SQLSyntax.IN_WITH_SPACE + "(:ids)";

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
                orConditions.add("(" + String.join(SQLSyntax.AND_WITH_SPACE, andConditions) + ")");
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
     * Adds a parameter to the parameter source with proper type conversion.
     */
    private void addTypedParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
        if (value == null || value.toString().isEmpty()) {
            paramSource.addValue(paramName, null);
            return;
        }

        String valueStr = value.toString();
        String type = columnType != null ? columnType.toLowerCase() : "";

        try {
            if (type.contains(ColumnTypeConstant.UUID)) {
                paramSource.addValue(paramName, UUID.fromString(valueStr));
            } else if (type.contains(ColumnTypeConstant.INT) && !type.contains(ColumnTypeConstant.BIGINT)) {
                paramSource.addValue(paramName, Integer.parseInt(valueStr));
            } else if (type.contains(ColumnTypeConstant.BIGINT)) {
                paramSource.addValue(paramName, Long.parseLong(valueStr));
            } else if (type.contains(ColumnTypeConstant.DECIMAL) || type.contains(ColumnTypeConstant.NUMERIC) || type.contains(ColumnTypeConstant.DOUBLE) || type.contains(ColumnTypeConstant.FLOAT)) {
                paramSource.addValue(paramName, Double.parseDouble(valueStr));
            } else if (type.contains(ColumnTypeConstant.BOOL)) {
                paramSource.addValue(paramName, Boolean.parseBoolean(valueStr));
            } else {
                paramSource.addValue(paramName, valueStr);
            }
        } catch (Exception e) {
            log.warn("Error converting cursor value for {} : {} ", paramName, e.getMessage());
            paramSource.addValue(paramName, valueStr);
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
     * Supports various operators like equals, contains, startsWith, endsWith,
     * greater than, less than, etc.
     *
     * @param arguments   The GraphQL filter arguments
     * @param paramSource The SQL parameter source to populate
     * @param columnTypes The types of columns in the table
     * @return A list of SQL WHERE conditions
     */
    private List<String> buildWhereConditions(Map<String, Object> arguments, MapSqlParameterSource paramSource,
                                              Map<String, String> columnTypes) {
        List<String> conditions = new ArrayList<>();
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

                switch (operator) {
                    case FieldConstant.OPERATOR_CONTAINS:
                        conditions.add(quotedFieldName + " LIKE :" + key);
                        paramSource.addValue(key, "%" + value + "%");
                        break;
                    case FieldConstant.OPERATOR_STARTS_WITH:
                        conditions.add(quotedFieldName + " LIKE :" + key);
                        paramSource.addValue(key, value + "%");
                        break;
                    case FieldConstant.OPERATOR_ENDS_WITH:
                        conditions.add(quotedFieldName + " LIKE :" + key);
                        paramSource.addValue(key, "%" + value);
                        break;
                    case FieldConstant.OPERATOR_GT:
                        conditions.add(quotedFieldName + " > :" + key);
                        paramSource.addValue(key, value);
                        break;
                    case FieldConstant.OPERATOR_GTE:
                        conditions.add(quotedFieldName + " >= :" + key);
                        paramSource.addValue(key, value);
                        break;
                    case FieldConstant.OPERATOR_LT:
                        conditions.add(quotedFieldName + " < :" + key);
                        paramSource.addValue(key, value);
                        break;
                    case FieldConstant.OPERATOR_LTE:
                        conditions.add(quotedFieldName + " <= :" + key);
                        paramSource.addValue(key, value);
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
                // Basic equality condition
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
                // Handle numeric type conversions
                else if (columnType.contains(ColumnTypeConstant.INT) || columnType.contains(ColumnTypeConstant.DECIMAL) ||
                        columnType.contains(ColumnTypeConstant.NUMERIC) || columnType.contains(ColumnTypeConstant.DOUBLE) ||
                        columnType.contains(ColumnTypeConstant.FLOAT) || columnType.contains(ColumnTypeConstant.BIGINT)
                        || columnType.contains(ColumnTypeConstant.SERIAL) || columnType.contains(ColumnTypeConstant.BIGSERIAL)) {

                    if (value instanceof String) {
                        try {
                            // Check if it's an integer type
                            if (columnType.contains(ColumnTypeConstant.INT) || columnType.contains(ColumnTypeConstant.SERIAL)) {
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
                } else {
                    conditions.add(quotedKey + " = :" + key);
                    paramSource.addValue(key, value);
                }
            }
        }

        return conditions;
    }
}
