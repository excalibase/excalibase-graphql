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
import io.github.excalibase.postgres.constant.PostgresTypeOperator;
import io.github.excalibase.constant.FieldConstant;
import io.github.excalibase.postgres.constant.PostgresSqlSyntaxConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PostgresSqlBuilder {
    private static final Logger log = LoggerFactory.getLogger(PostgresSqlBuilder.class);
    
    private final PostgresTypeConverter typeConverter;

    public PostgresSqlBuilder(PostgresTypeConverter typeConverter) {
        this.typeConverter = typeConverter;
    }

    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public String getQualifiedTableName(String tableName, String allowedSchema) {
        return allowedSchema + "." + quoteIdentifier(tableName);
    }

    public String buildColumnList(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return "*";
        }
        return columns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
    }

    public String buildInsertSql(String tableName, String allowedSchema, Set<String> fieldNames, Map<String, String> columnTypes) {
        return PostgresSqlSyntaxConstant.INSERT_INTO_WITH_SPACE + getQualifiedTableName(tableName, allowedSchema) + " (" +
               fieldNames.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")) +
               ") VALUES (" +
               fieldNames.stream().map(field -> buildParameterWithCasting(field, columnTypes)).collect(Collectors.joining(", ")) +
               ")" + PostgresSqlSyntaxConstant.RETURNING_ALL;
    }

    public String buildUpdateSql(String tableName, String allowedSchema, Set<String> updateFields, Set<String> whereFields, Map<String, String> columnTypes) {
        return PostgresSqlSyntaxConstant.UPDATE_WITH_SPACE + getQualifiedTableName(tableName, allowedSchema) + PostgresSqlSyntaxConstant.SET_WITH_SPACE +
               updateFields.stream().map(field -> 
                   quoteIdentifier(field) + " = " + buildParameterWithCasting(field, columnTypes)
               ).collect(Collectors.joining(", ")) +
               PostgresSqlSyntaxConstant.WHERE_WITH_SPACE +
               whereFields.stream().map(field -> 
                   quoteIdentifier(field) + " = " + buildParameterWithCasting(field, columnTypes)
               ).collect(Collectors.joining(PostgresSqlSyntaxConstant.AND_WITH_SPACE)) +
               PostgresSqlSyntaxConstant.RETURNING_ALL;
    }

    public String buildDeleteSql(String tableName, String allowedSchema, Set<String> whereFields, Map<String, String> columnTypes) {
        return PostgresSqlSyntaxConstant.DELETE_WITH_SPACE + getQualifiedTableName(tableName, allowedSchema) +
               PostgresSqlSyntaxConstant.WHERE_WITH_SPACE +
               whereFields.stream().map(field -> 
                   quoteIdentifier(field) + " = " + buildParameterWithCasting(field, columnTypes)
               ).collect(Collectors.joining(PostgresSqlSyntaxConstant.AND_WITH_SPACE)) +
               PostgresSqlSyntaxConstant.RETURNING_ALL;
    }

    public String buildBulkInsertSql(String tableName, String allowedSchema, Set<String> allFields, int recordCount, Map<String, String> columnTypes) {
        StringBuilder sql = new StringBuilder(PostgresSqlSyntaxConstant.INSERT_INTO_WITH_SPACE)
            .append(getQualifiedTableName(tableName, allowedSchema))
            .append(" (")
            .append(allFields.stream().map(this::quoteIdentifier).collect(Collectors.joining(", ")))
            .append(") VALUES ");
        
        for (int i = 0; i < recordCount; i++) {
            if (i > 0) sql.append(", ");
            final int index = i; // Make it final for use in stream
            sql.append("(")
               .append(allFields.stream().map(field -> buildBulkParameterWithCasting(field, index, columnTypes)).collect(Collectors.joining(", ")))
               .append(")");
        }
        
        sql.append(PostgresSqlSyntaxConstant.RETURNING_ALL);
        return sql.toString();
    }

    public List<String> buildWhereConditions(Map<String, Object> arguments, MapSqlParameterSource paramSource, Map<String, String> columnTypes) {
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
                    fieldName = key.substring(0, key.length() - op.length() - 1);
                    operator = op;
                    isOperatorFilter = true;
                    break;
                }
            }

            if (isOperatorFilter) {
                String quotedFieldName = "\"" + fieldName + "\"";
                String fieldColumnType = columnTypes.getOrDefault(fieldName, "").toLowerCase();
                conditions.add(buildLegacyOperatorCondition(quotedFieldName, operator, key, value, fieldColumnType, paramSource));
            } else {
                // Basic equality condition (legacy format)
                String columnType = columnTypes.getOrDefault(key, "").toLowerCase();
                String quotedKey = "\"" + key + "\"";
                conditions.add(buildBasicEqualityCondition(quotedKey, key, value, columnType, paramSource));
            }
        }

        return conditions;
    }

    public void buildCursorConditions(List<String> cursorConditions, Map<String, Object> cursorValues, 
                                    Map<String, String> orderByFields, Map<String, String> columnTypes, 
                                    MapSqlParameterSource paramSource, String direction) {
        List<String> orderFields = new ArrayList<>(orderByFields.keySet());

        List<String> orConditions = new ArrayList<>();

        for (int i = 0; i < orderFields.size(); i++) {
            List<String> andConditions = new ArrayList<>();

            for (int j = 0; j < i; j++) {
                String field = orderFields.get(j);
                String paramName = "cursor_" + field + "_" + direction;
                andConditions.add(quoteIdentifier(field) + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName);
                typeConverter.addTypedParameter(paramSource, paramName, cursorValues.get(field), columnTypes.get(field));
            }

            String currentField = orderFields.get(i);
            String currentDirection = orderByFields.get(currentField);
            String paramName = "cursor_" + currentField + "_" + direction;

            String operator;
            if (FieldConstant.AFTER.equals(direction)) {
                operator = PostgresSqlSyntaxConstant.ASC_ORDER.equalsIgnoreCase(currentDirection) ? ">" : "<";
            } else {
                operator = PostgresSqlSyntaxConstant.ASC_ORDER.equalsIgnoreCase(currentDirection) ? "<" : ">";
            }

            andConditions.add(quoteIdentifier(currentField) + " " + operator + " :" + paramName);
            typeConverter.addTypedParameter(paramSource, paramName, cursorValues.get(currentField), columnTypes.get(currentField));
            
            if (andConditions.size() == 1) {
                orConditions.add(andConditions.getFirst());
            } else {
                orConditions.add("(" + String.join(PostgresSqlSyntaxConstant.AND_WITH_SPACE, andConditions) + ")");
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

    private String buildParameterWithCasting(String field, Map<String, String> columnTypes) {
        String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
        
        if (PostgresTypeOperator.isArrayType(columnType)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType.contains(ColumnTypeConstant.JSON)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.INET) || columnType.contains(ColumnTypeConstant.CIDR) || columnType.contains(ColumnTypeConstant.MACADDR)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.TIMESTAMP) || columnType.contains(ColumnTypeConstant.TIME)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.XML)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.XML;
        } else if (columnType.contains(ColumnTypeConstant.BYTEA)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.BYTEA;
        } else if (typeConverter.isCustomEnumType(columnType)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (typeConverter.isCustomCompositeType(columnType)) {
            return ":" + field + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else {
            return ":" + field;
        }
    }

    private String buildBulkParameterWithCasting(String field, int index, Map<String, String> columnTypes) {
        String columnType = columnTypes.getOrDefault(field, "").toLowerCase();
        String paramName = field + "_" + index;
        
        if (PostgresTypeOperator.isArrayType(columnType)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType.contains(ColumnTypeConstant.JSON)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.INET) || columnType.contains(ColumnTypeConstant.CIDR) || columnType.contains(ColumnTypeConstant.MACADDR)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.TIMESTAMP) || columnType.contains(ColumnTypeConstant.TIME)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.XML)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.XML;
        } else if (columnType.contains(ColumnTypeConstant.BYTEA)) {
            return ":" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.BYTEA;
        } else {
            return ":" + paramName;
        }
    }

    private List<String> buildFilterConditions(Map<String, Object> filterObj, MapSqlParameterSource paramSource, 
                                               Map<String, String> columnTypes, String paramPrefix) {
        List<String> conditions = new ArrayList<>();
        
        for (Map.Entry<String, Object> entry : filterObj.entrySet()) {
            String columnName = entry.getKey();
            Object filterValue = entry.getValue();
            
            if (filterValue == null) {
                continue;
            }
            
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
                
                String condition = buildFilterCondition(quotedColumnName, operator, paramName, value, columnType, isInterval, paramSource);
                if (condition != null) {
                    conditions.add(condition);
                }
            }
        }
        
        return conditions;
    }

    private String buildFilterCondition(String quotedColumnName, String operator, String paramName, Object value, 
                                      String columnType, boolean isInterval, MapSqlParameterSource paramSource) {
        switch (operator.toLowerCase()) {
            case "eq":
                return buildEqualityCondition(quotedColumnName, paramName, value, columnType, isInterval, paramSource);
            case "neq":
                return buildInequalityCondition(quotedColumnName, paramName, value, columnType, isInterval, paramSource);
            case "gt":
                return buildComparisonCondition(quotedColumnName, paramName, value, columnType, isInterval, ">", paramSource);
            case "gte":
                return buildComparisonCondition(quotedColumnName, paramName, value, columnType, isInterval, ">=", paramSource);
            case "lt":
                return buildComparisonCondition(quotedColumnName, paramName, value, columnType, isInterval, "<", paramSource);
            case "lte":
                return buildComparisonCondition(quotedColumnName, paramName, value, columnType, isInterval, "<=", paramSource);
            case "like":
                paramSource.addValue(paramName, "%" + value + "%");
                return quotedColumnName + " LIKE :" + paramName;
            case "ilike":
                paramSource.addValue(paramName, "%" + value + "%");
                return quotedColumnName + " ILIKE :" + paramName;
            case "contains":
                return buildContainsCondition(quotedColumnName, paramName, value, columnType, paramSource);
            case "startswith":
                return buildStartsWithCondition(quotedColumnName, paramName, value, columnType, paramSource);
            case "endswith":
                return buildEndsWithCondition(quotedColumnName, paramName, value, columnType, paramSource);
            case "isnull":
                if (value instanceof Boolean && (Boolean) value) {
                    return quotedColumnName + PostgresSqlSyntaxConstant.IS_NULL;
                } else {
                    return quotedColumnName + PostgresSqlSyntaxConstant.IS_NOT_NULL;
                }
            case "isnotnull":
                if (value instanceof Boolean && (Boolean) value) {
                    return quotedColumnName + PostgresSqlSyntaxConstant.IS_NOT_NULL;
                } else {
                    return quotedColumnName + PostgresSqlSyntaxConstant.IS_NULL;
                }
            case "in":
                return buildInCondition(quotedColumnName, paramName, value, columnType, isInterval, paramSource);
            case "notin":
                return buildNotInCondition(quotedColumnName, paramName, value, columnType, isInterval, paramSource);
            default:
                return null;
        }
    }

    private String buildEqualityCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                        boolean isInterval, MapSqlParameterSource paramSource) {
        if (isInterval) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType != null && PostgresTypeOperator.isNetworkType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType != null && PostgresTypeOperator.isDateTimeType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType != null && PostgresTypeOperator.isXmlType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName;
        } else if (columnType != null && typeConverter.isCustomEnumType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.EQUALS_PARAM + paramName;
        }
    }

    private String buildInequalityCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                          boolean isInterval, MapSqlParameterSource paramSource) {
        if (isInterval) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType != null && PostgresTypeOperator.isNetworkType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType != null && PostgresTypeOperator.isDateTimeType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType != null && PostgresTypeOperator.isXmlType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName;
        } else if (columnType != null && typeConverter.isCustomEnumType(columnType)) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + PostgresSqlSyntaxConstant.NOT_EQUALS_PARAM + paramName;
        }
    }

    private String buildComparisonCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                          boolean isInterval, String operator, MapSqlParameterSource paramSource) {
        if (isInterval) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + " " + operator + " :" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType != null && (columnType.contains(ColumnTypeConstant.TIMESTAMP) || columnType.contains(ColumnTypeConstant.TIME))) {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + " " + operator + " :" + paramName + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else {
            typeConverter.addTypedParameter(paramSource, paramName, value, columnType);
            return quotedColumnName + " " + operator + " :" + paramName;
        }
    }

    private String buildContainsCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                        MapSqlParameterSource paramSource) {
        if (columnType != null && (columnType.toLowerCase().contains(ColumnTypeConstant.JSON))) {
            paramSource.addValue(paramName, "%" + value + "%");
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " LIKE :" + paramName;
        } else if (columnType != null && columnType.toLowerCase().contains(ColumnTypeConstant.XML)) {
            paramSource.addValue(paramName, "%" + value + "%");
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " LIKE :" + paramName;
        } else if (columnType != null && (columnType.contains(ColumnTypeConstant.INET) || columnType.contains(ColumnTypeConstant.CIDR) || columnType.contains(ColumnTypeConstant.MACADDR))) {
            paramSource.addValue(paramName, "%" + value + "%");
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + paramName;
        } else {
            paramSource.addValue(paramName, "%" + value + "%");
            return quotedColumnName + " LIKE :" + paramName;
        }
    }

    private String buildStartsWithCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                          MapSqlParameterSource paramSource) {
        if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
            paramSource.addValue(paramName, value + "%");
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + paramName;
        } else {
            paramSource.addValue(paramName, value + "%");
            return quotedColumnName + " LIKE :" + paramName;
        }
    }

    private String buildEndsWithCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                        MapSqlParameterSource paramSource) {
        if (columnType != null && (columnType.contains("inet") || columnType.contains("cidr") || columnType.contains("macaddr"))) {
            paramSource.addValue(paramName, "%" + value);
            return quotedColumnName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + paramName;
        } else {
            paramSource.addValue(paramName, "%" + value);
            return quotedColumnName + " LIKE :" + paramName;
        }
    }

    private String buildInCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                  boolean isInterval, MapSqlParameterSource paramSource) {
        if (value instanceof List) {
            List<?> valueList = (List<?>) value;
            if (!valueList.isEmpty()) {
                if (isInterval) {
                    StringBuilder inClause = new StringBuilder("(");
                    for (int i = 0; i < valueList.size(); i++) {
                        if (i > 0) inClause.append(", ");
                        String itemParamName = paramName + "_" + i;
                        inClause.append(":").append(itemParamName).append(PostgresSqlSyntaxConstant.CAST_OPERATOR).append(ColumnTypeConstant.INTERVAL);
                        paramSource.addValue(itemParamName, valueList.get(i).toString());
                    }
                    inClause.append(")");
                    return quotedColumnName + " IN " + inClause.toString();
                } else {
                    typeConverter.addTypedParameter(paramSource, paramName, valueList, columnType);
                    return quotedColumnName + " IN (:" + paramName + ")";
                }
            }
        }
        return null;
    }

    private String buildNotInCondition(String quotedColumnName, String paramName, Object value, String columnType, 
                                     boolean isInterval, MapSqlParameterSource paramSource) {
        if (value instanceof List) {
            List<?> valueList = (List<?>) value;
            if (!valueList.isEmpty()) {
                if (isInterval) {
                    StringBuilder notInClause = new StringBuilder("(");
                    for (int i = 0; i < valueList.size(); i++) {
                        if (i > 0) notInClause.append(", ");
                        String itemParamName = paramName + "_" + i;
                        notInClause.append(":").append(itemParamName).append(PostgresSqlSyntaxConstant.CAST_OPERATOR).append(ColumnTypeConstant.INTERVAL);
                        paramSource.addValue(itemParamName, valueList.get(i).toString());
                    }
                    notInClause.append(")");
                    return quotedColumnName + " NOT IN " + notInClause.toString();
                } else {
                    typeConverter.addTypedParameter(paramSource, paramName, valueList, columnType);
                    return quotedColumnName + " NOT IN (:" + paramName + ")";
                }
            }
        }
        return null;
    }

    private String buildLegacyOperatorCondition(String quotedFieldName, String operator, String key, Object value, 
                                              String fieldColumnType, MapSqlParameterSource paramSource) {
        switch (operator) {
            case FieldConstant.OPERATOR_CONTAINS:
                return buildLegacyContainsCondition(quotedFieldName, key, value, fieldColumnType, paramSource);
            case FieldConstant.OPERATOR_STARTS_WITH:
                return buildLegacyStartsWithCondition(quotedFieldName, key, value, fieldColumnType, paramSource);
            case FieldConstant.OPERATOR_ENDS_WITH:
                return buildLegacyEndsWithCondition(quotedFieldName, key, value, fieldColumnType, paramSource);
            case FieldConstant.OPERATOR_GT:
                return buildLegacyComparisonCondition(quotedFieldName, key, value, fieldColumnType, ">", paramSource);
            case FieldConstant.OPERATOR_GTE:
                return buildLegacyComparisonCondition(quotedFieldName, key, value, fieldColumnType, ">=", paramSource);
            case FieldConstant.OPERATOR_LT:
                return buildLegacyComparisonCondition(quotedFieldName, key, value, fieldColumnType, "<", paramSource);
            case FieldConstant.OPERATOR_LTE:
                return buildLegacyComparisonCondition(quotedFieldName, key, value, fieldColumnType, "<=", paramSource);
            case FieldConstant.OPERATOR_IS_NULL:
                if (value instanceof Boolean && (Boolean) value) {
                    return quotedFieldName + PostgresSqlSyntaxConstant.IS_NULL;
                } else {
                    return quotedFieldName + PostgresSqlSyntaxConstant.IS_NOT_NULL;
                }
            case FieldConstant.OPERATOR_IS_NOT_NULL:
                if (value instanceof Boolean && (Boolean) value) {
                    return quotedFieldName + PostgresSqlSyntaxConstant.IS_NOT_NULL;
                } else {
                    return quotedFieldName + PostgresSqlSyntaxConstant.IS_NULL;
                }
            default:
                return null;
        }
    }

    private String buildLegacyContainsCondition(String quotedFieldName, String key, Object value, String fieldColumnType, 
                                              MapSqlParameterSource paramSource) {
        if (fieldColumnType.contains("json")) {
            paramSource.addValue(key, "%" + value + "%");
            return quotedFieldName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " LIKE :" + key;
        } else if (fieldColumnType.contains("xml")) {
            paramSource.addValue(key, "%" + value + "%");
            return quotedFieldName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " LIKE :" + key;
        } else if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr")) {
            paramSource.addValue(key, "%" + value + "%");
            return quotedFieldName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + key;
        } else {
            paramSource.addValue(key, "%" + value + "%");
            return quotedFieldName + " LIKE :" + key;
        }
    }

    private String buildLegacyStartsWithCondition(String quotedFieldName, String key, Object value, String fieldColumnType, 
                                                MapSqlParameterSource paramSource) {
        if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr") || fieldColumnType.contains("xml")) {
            paramSource.addValue(key, value + "%");
            return quotedFieldName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + key;
        } else {
            paramSource.addValue(key, value + "%");
            return quotedFieldName + " LIKE :" + key;
        }
    }

    private String buildLegacyEndsWithCondition(String quotedFieldName, String key, Object value, String fieldColumnType, 
                                              MapSqlParameterSource paramSource) {
        if (fieldColumnType.contains("inet") || fieldColumnType.contains("cidr") || fieldColumnType.contains("macaddr") || fieldColumnType.contains("xml")) {
            paramSource.addValue(key, "%" + value);
            return quotedFieldName + PostgresSqlSyntaxConstant.CAST_TO_TEXT + " ILIKE :" + key;
        } else {
            paramSource.addValue(key, "%" + value);
            return quotedFieldName + " LIKE :" + key;
        }
    }

    private String buildLegacyComparisonCondition(String quotedFieldName, String key, Object value, String fieldColumnType, 
                                                String operator, MapSqlParameterSource paramSource) {
        if (fieldColumnType.contains(ColumnTypeConstant.INTERVAL)) {
            paramSource.addValue(key, value.toString());
            return quotedFieldName + " " + operator + " :" + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (fieldColumnType.contains(ColumnTypeConstant.TIMESTAMP) || fieldColumnType.contains(ColumnTypeConstant.TIME)) {
            paramSource.addValue(key, value.toString());
            return quotedFieldName + " " + operator + " :" + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + fieldColumnType;
        } else {
            paramSource.addValue(key, value);
            return quotedFieldName + " " + operator + " :" + key;
        }
    }

    private String buildBasicEqualityCondition(String quotedKey, String key, Object value, String columnType, 
                                             MapSqlParameterSource paramSource) {
        if (columnType.contains(ColumnTypeConstant.UUID) && value instanceof String) {
            try {
                java.util.UUID uuid = java.util.UUID.fromString((String) value);
                paramSource.addValue(key, uuid);
                return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key;
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format: {}", value);
                throw new RuntimeException("Invalid UUID format for column " + key + ": " + value, e);
            }
        } else if (columnType.contains(ColumnTypeConstant.INTERVAL)) {
            paramSource.addValue(key, value.toString());
            return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + ColumnTypeConstant.INTERVAL;
        } else if (columnType.contains(ColumnTypeConstant.INET) || columnType.contains(ColumnTypeConstant.CIDR) || columnType.contains(ColumnTypeConstant.MACADDR)) {
            paramSource.addValue(key, value.toString());
            return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (columnType.contains(ColumnTypeConstant.TIMESTAMP) || columnType.contains(ColumnTypeConstant.TIME)) {
            paramSource.addValue(key, value.toString());
            return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else if (PostgresTypeOperator.isIntegerType(columnType) || PostgresTypeOperator.isFloatingPointType(columnType)) {
            return buildNumericEqualityCondition(quotedKey, key, value, columnType, paramSource);
        } else if (typeConverter.isCustomEnumType(columnType)) {
            paramSource.addValue(key, value.toString());
            return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key + PostgresSqlSyntaxConstant.CAST_OPERATOR + columnType;
        } else {
            paramSource.addValue(key, value);
            return quotedKey + PostgresSqlSyntaxConstant.EQUALS_PARAM + key;
        }
    }

    private String buildNumericEqualityCondition(String quotedKey, String key, Object value, String columnType, 
                                                MapSqlParameterSource paramSource) {
        if (value instanceof String) {
            try {
                if (PostgresTypeOperator.isIntegerType(columnType)) {
                    int numericValue = Integer.parseInt((String) value);
                    paramSource.addValue(key, numericValue);
                } else if (columnType.contains(ColumnTypeConstant.BIGINT)) {
                    long numericValue = Long.parseLong((String) value);
                    paramSource.addValue(key, numericValue);
                } else {
                    double numericValue = Double.parseDouble((String) value);
                    paramSource.addValue(key, numericValue);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid numeric format for column {} : {}", key, value);
                paramSource.addValue(key, value);
            }
        } else {
            paramSource.addValue(key, value);
        }
        return quotedKey + " = :" + key;
    }
}