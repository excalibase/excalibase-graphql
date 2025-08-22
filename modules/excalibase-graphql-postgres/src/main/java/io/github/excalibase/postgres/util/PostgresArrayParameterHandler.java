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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PostgresArrayParameterHandler {
    private static final Logger log = LoggerFactory.getLogger(PostgresArrayParameterHandler.class);
    
    private final PostgresTypeConverter typeConverter;

    public PostgresArrayParameterHandler(PostgresTypeConverter typeConverter) {
        this.typeConverter = typeConverter;
    }

    public void addTypedParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
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

            // Handle JSON/JSONB types - MUST come before composite types
            if (PostgresTypeOperator.isJsonType(columnType)) {
                handleJsonParameter(paramSource, paramName, value);
                return;
            }
            
            // Handle custom composite types
            if (typeConverter.isCustomCompositeType(columnType)) {
                if (value instanceof java.util.Map) {
                    String compositeValue = typeConverter.convertMapToPostgresComposite((java.util.Map<String, Object>) value, columnType);
                    paramSource.addValue(paramName, compositeValue);
                    return;
                } else if (value instanceof String) {
                    // String input for composite types - pass directly to PostgreSQL
                    paramSource.addValue(paramName, value.toString());
                    return;
                }
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
                    java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf(((String) value).replace('T', ' ').replace('Z', ' ').trim());
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
     * Handles JSON/JSONB parameter conversion.
     * Converts JsonNode objects to JSON strings for PostgreSQL.
     */
    private void handleJsonParameter(MapSqlParameterSource paramSource, String paramName, Object value) {
        if (value == null) {
            paramSource.addValue(paramName, null);
            return;
        }
        
        try {
            String jsonString;
            
            // If it's a JsonNode (from our enhanced JsonScalar), convert to string
            if (value instanceof JsonNode) {
                ObjectMapper mapper = new ObjectMapper();
                jsonString = mapper.writeValueAsString(value);
            }
            // If it's already a JSON string, validate and use it
            else if (value instanceof String) {
                String stringValue = (String) value;
                // Validate it's valid JSON
                ObjectMapper mapper = new ObjectMapper();
                mapper.readTree(stringValue); // This will throw if invalid
                jsonString = stringValue;
            }
            // If it's a Map or List, convert to JSON string
            else if (value instanceof java.util.Map || value instanceof java.util.List) {
                ObjectMapper mapper = new ObjectMapper();
                jsonString = mapper.writeValueAsString(value);
            }
            // For other types, try to convert to JSON
            else {
                ObjectMapper mapper = new ObjectMapper();
                jsonString = mapper.writeValueAsString(value);
            }
            
            // PostgreSQL JSONB columns need the value as a String
            paramSource.addValue(paramName, jsonString);
            
        } catch (JsonProcessingException e) {
            log.error("Error converting JSON parameter {}: {}", paramName, e.getMessage());
            // Fallback: use toString() and let PostgreSQL handle it
            paramSource.addValue(paramName, value.toString());
        } catch (Exception e) {
            log.error("Unexpected error handling JSON parameter {}: {}", paramName, e.getMessage());
            paramSource.addValue(paramName, value.toString());
        }
    }

    public void handleArrayParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
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

    public String mapToPGArrayTypeName(String baseType) {
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
        } else if (baseType.contains(ColumnTypeConstant.VARCHAR) || baseType.contains("character varying")) {
            return ColumnTypeConstant.TEXT; // Use text for varchar arrays for simplicity
        } else if (baseType.contains("decimal") || baseType.contains("numeric")) {
            return "numeric";
        } else {
            return ColumnTypeConstant.TEXT; // Default to text for other types
        }
    }

    public String formatArrayForPostgreSQL(Object[] array, String baseType) {
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

    public boolean needsQuoting(String pgTypeName) {
        return ColumnTypeConstant.TEXT.equals(pgTypeName) || 
               pgTypeName.contains(ColumnTypeConstant.VARCHAR) || 
               pgTypeName.contains(ColumnTypeConstant.CHAR) ||
               pgTypeName.contains(ColumnTypeConstant.UUID);
    }

    public Object[] convertListToTypedArray(List<?> listValue, String baseType) {
        return listValue.stream()
                .map(element -> convertArrayElement(element, baseType))
                .toArray();
    }

    public Object convertArrayElement(Object element, String baseType) {
        if (element == null) {
            return null;
        }

        // Handle JSON types - convert JsonNode to string
        if (PostgresTypeOperator.isJsonType(baseType)) {
            try {
                if (element instanceof JsonNode) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.writeValueAsString(element);
                } else if (element instanceof java.util.Map || element instanceof java.util.List) {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.writeValueAsString(element);
                } else {
                    return element.toString();
                }
            } catch (JsonProcessingException e) {
                log.warn("Error converting JSON array element: {}", e.getMessage());
                return element.toString();
            }
        }
        
        // Handle custom enum types
        if (typeConverter.isCustomEnumType(baseType)) {
            return element.toString(); // Custom enums are stored as strings
        }
        
        // Handle custom composite types
        if (typeConverter.isCustomCompositeType(baseType)) {
            if (element instanceof java.util.Map) {
                // Convert Map to PostgreSQL composite format
                return typeConverter.convertMapToPostgresComposite((java.util.Map<String, Object>) element, baseType);
            } else {
                // Already in string format, pass through
                return element.toString();
            }
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
}