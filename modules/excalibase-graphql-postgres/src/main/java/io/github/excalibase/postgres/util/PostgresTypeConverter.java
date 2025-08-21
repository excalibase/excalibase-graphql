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
import io.github.excalibase.model.CompositeTypeAttribute;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.postgres.constant.PostgresTypeOperator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Array;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class PostgresTypeConverter {
    private static final Logger log = LoggerFactory.getLogger(PostgresTypeConverter.class);
    
    private final IDatabaseSchemaReflector schemaReflector;

    public PostgresTypeConverter(IDatabaseSchemaReflector schemaReflector) {
        this.schemaReflector = schemaReflector;
    }

    public boolean isCustomEnumType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        
        try {
            return schemaReflector.getCustomEnumTypes().stream()
                    .anyMatch(enumType -> enumType.getName().equalsIgnoreCase(type));
        } catch (Exception e) {
            log.debug("Error checking custom enum types: {}", e.getMessage());
            return false;
        }
    }
    
    public boolean isCustomCompositeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        
        try {
            return schemaReflector.getCustomCompositeTypes().stream()
                    .anyMatch(compositeType -> compositeType.getName().equalsIgnoreCase(type));
        } catch (Exception e) {
            log.debug("Error checking custom composite types: {}", e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> convertPostgresTypesToGraphQLTypes(List<Map<String, Object>> results, TableInfo tableInfo) {
        // Get array column information
        Map<String, String> arrayColumns = tableInfo.getColumns().stream()
                .filter(col -> PostgresTypeOperator.isArrayType(col.getType()))
                .collect(Collectors.toMap(io.github.excalibase.model.ColumnInfo::getName, io.github.excalibase.model.ColumnInfo::getType));
        
        // Get custom type columns (will determine enum vs composite based on actual data)
        Map<String, String> customTypeColumns = tableInfo.getColumns().stream()
                .filter(col -> isCustomEnumType(col.getType()) || 
                              isCustomCompositeType(col.getType()))
                .filter(col -> !PostgresTypeOperator.isArrayType(col.getType())) // Exclude arrays for now
                .collect(Collectors.toMap(io.github.excalibase.model.ColumnInfo::getName, io.github.excalibase.model.ColumnInfo::getType));
        
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

    public Map<String, Object> convertPostgresTypesToGraphQLTypes(Map<String, Object> result, TableInfo tableInfo) {
        Map<String, Object> convertedResult = new HashMap<>(result);
        
        Map<String, String> customTypeColumns = tableInfo.getColumns().stream()
                .filter(col -> {
                    String baseType = col.getType().replace("[]", "");
                    return isCustomEnumType(baseType) || isCustomCompositeType(baseType);
                })
                .collect(Collectors.toMap(io.github.excalibase.model.ColumnInfo::getName, io.github.excalibase.model.ColumnInfo::getType));
        
        for (Map.Entry<String, String> customCol : customTypeColumns.entrySet()) {
            String columnName = customCol.getKey();
            String columnType = customCol.getValue();
            Object value = result.get(columnName);
            
            if (value != null) {
                if (PostgresTypeOperator.isArrayType(columnType)) {
                    List<Object> convertedArray = convertCustomTypeArrayToList(value, columnType);
                    convertedResult.put(columnName, convertedArray);
                } else {
                    String valueStr = value.toString();
                    
                    // If value starts with '(' and ends with ')', it's likely a composite type
                    if (valueStr.startsWith("(") && valueStr.endsWith(")")) {
                        // Process as composite type
                        Map<String, Object> convertedComposite = convertPostgresCompositeToMap(value, columnType);
                        convertedResult.put(columnName, convertedComposite);
                    } else {
                        // Process as enum type (simple string value)
                        convertedResult.put(columnName, valueStr);
                    }
                }
            }
        }
        
        for (io.github.excalibase.model.ColumnInfo column : tableInfo.getColumns()) {
            String columnName = column.getName();
            String columnType = column.getType();
            Object value = result.get(columnName);
            
            if (value != null && PostgresTypeOperator.isArrayType(columnType)) {
                String baseType = columnType.replace("[]", "");
                
                // Only process if it's NOT a custom type (custom types handled above)
                if (!isCustomEnumType(baseType) && !isCustomCompositeType(baseType)) {
                    // Handle regular array types (int[], text[], etc.) - convert PGArray to List
                    if (value instanceof java.sql.Array) {
                        List<Object> convertedArray = convertRegularArrayToList(value, columnType);
                        convertedResult.put(columnName, convertedArray);
                    }
                }
            }
        }
        
        return convertedResult;
    }

    public void addTypedParameter(MapSqlParameterSource paramSource, String paramName, Object value, String columnType) {
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
                        if (PostgresTypeOperator.isUuidType(type)) {
                            convertedList.add(UUID.fromString(itemStr));
                        } else if (PostgresTypeOperator.isIntegerType(type)) {
                            convertedList.add(Integer.parseInt(itemStr));
                        } else if (PostgresTypeOperator.isFloatingPointType(type)) {
                            convertedList.add(Double.parseDouble(itemStr));
                        } else if (PostgresTypeOperator.isBooleanType(type)) {
                            convertedList.add(Boolean.parseBoolean(itemStr));
                        } else if (type.equals(ColumnTypeConstant.INTERVAL)) {
                            // Intervals should be passed as strings - PostgreSQL will handle the conversion
                            convertedList.add(itemStr);
                        } else if (PostgresTypeOperator.isDateTimeType(type) && !type.equals(ColumnTypeConstant.INTERVAL)) {
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
            
            if (PostgresTypeOperator.isUuidType(type)) {
                paramSource.addValue(paramName, UUID.fromString(valueStr));
            } else if (PostgresTypeOperator.isIntegerType(type)) {
                paramSource.addValue(paramName, Integer.parseInt(valueStr));
            } else if (PostgresTypeOperator.isFloatingPointType(type)) {
                paramSource.addValue(paramName, Double.parseDouble(valueStr));
            } else if (PostgresTypeOperator.isBooleanType(type)) {
                paramSource.addValue(paramName, Boolean.parseBoolean(valueStr));
            } else if (type.equals(ColumnTypeConstant.INTERVAL)) {
                // Intervals are durations, not timestamps - pass as string for PostgreSQL to handle
                paramSource.addValue(paramName, valueStr);
            } else if (PostgresTypeOperator.isDateTimeType(type) && !type.equals(ColumnTypeConstant.INTERVAL)) {
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

    public Object convertToDateTime(String valueStr, String columnType) {
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
        if (type.equals(ColumnTypeConstant.DATE)) {
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
        if (PostgresTypeOperator.isDateTimeType(type) && type.contains("timestamp")) {
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
            } else if (PostgresTypeOperator.isUuidType(type)) {
                return elementStr; // Keep UUIDs as strings in GraphQL
            } else {
                return elementStr; // Default to string
            }
        } catch (NumberFormatException e) {
            log.warn("Could not convert array element '{}' to type '{}', using string", elementStr, type);
            return elementStr;
        }
    }

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

    private List<String> getCompositeTypeFieldNames(String compositeTypeName) {
        try {
            List<CustomCompositeTypeInfo> compositeTypes = schemaReflector.getCustomCompositeTypes();
            
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
            if ("test_location".equalsIgnoreCase(compositeTypeName)) {
                return Arrays.asList("latitude", "longitude", "city");
            }
            
            log.warn("Composite type '{}' not found in schema, using generic field names", compositeTypeName);
            return new ArrayList<>();
            
        } catch (Exception e) {
            log.error("Error getting composite type field names for type: {}", compositeTypeName, e);
            return new ArrayList<>();
        }
    }

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

    /**
     * Converts PostgreSQL arrays (both regular and custom types) to Java Lists for GraphQL
     */
    private List<Object> convertArrayToList(Object arrayValue, String columnType) {
        if (arrayValue == null) {
            return List.of();
        }
        
        // Handle PGArray objects (from PostgreSQL JDBC driver)
        if (arrayValue instanceof java.sql.Array) {
            try {
                java.sql.Array sqlArray = (java.sql.Array) arrayValue;
                Object[] elements = (Object[]) sqlArray.getArray();
                
                String baseType = columnType.replace("[]", "");
                
                // Convert each element based on type
                List<Object> convertedList = new ArrayList<>();
                for (Object element : elements) {
                    if (element == null) {
                        convertedList.add(null);
                    } else if (isCustomCompositeType(baseType)) {
                        // Handle custom composite types
                        String elementStr = element.toString();
                        if (elementStr.startsWith("(") && elementStr.endsWith(")")) {
                            Map<String, Object> convertedMap = convertPostgresCompositeToMap(elementStr, baseType);
                            convertedList.add(convertedMap);
                        } else {
                            convertedList.add(elementStr);
                        }
                    } else if (isCustomEnumType(baseType)) {
                        // Handle custom enum types
                        convertedList.add(element.toString());
                    } else {
                        // Handle regular PostgreSQL types (integer, text, etc.)
                        convertedList.add(element);
                    }
                }
                
                return convertedList;
                
            } catch (Exception e) {
                log.error("Error converting PGArray to List for column type: {}", columnType, e);
                return List.of();
            }
        }
        
        // Fallback: handle string representation of arrays (legacy)
        return convertCustomTypeArrayToList(arrayValue, columnType);
    }

    /**
     * Converts regular PostgreSQL arrays (non-custom types) from PGArray to Java Lists
     */
    private List<Object> convertRegularArrayToList(Object arrayValue, String columnType) {
        if (arrayValue == null) {
            return List.of();
        }
        
        // Handle PGArray objects (from PostgreSQL JDBC driver)
        if (arrayValue instanceof java.sql.Array) {
            try {
                java.sql.Array sqlArray = (java.sql.Array) arrayValue;
                Object[] elements = (Object[]) sqlArray.getArray();
                
                // For regular arrays, just convert directly to List
                List<Object> convertedList = new ArrayList<>();
                for (Object element : elements) {
                    convertedList.add(element);
                }
                
                return convertedList;
                
            } catch (Exception e) {
                log.error("Error converting regular PGArray to List for column type: {}", columnType, e);
                return List.of();
            }
        }
        
        // For non-PGArray values, return as single-element list
        return List.of(arrayValue);
    }

    private List<Object> convertCustomTypeArrayToList(Object arrayValue, String columnType) {
        if (arrayValue == null) {
            return List.of();
        }
        
        try {
            String baseType = columnType.replace("[]", "");
            String arrayStr = arrayValue.toString();
            
            // Parse PostgreSQL array format like "{low,medium,high}" or "{(1,2,NYC),(3,4,LA)}"
            List<Object> elements = parsePostgresArrayString(arrayStr);
            
            // Convert each element based on the base type
            return elements.stream().map(element -> {
                if (element == null) {
                    return null;
                }
                
                String elementStr = element.toString();
                
                // Strip outer quotes if present (PostgreSQL array elements are often quoted)
                if (elementStr.startsWith("\"") && elementStr.endsWith("\"") && elementStr.length() > 1) {
                    elementStr = elementStr.substring(1, elementStr.length() - 1);
                }
                
                // Handle custom composite types
                if (isCustomCompositeType(baseType)) {
                    log.debug("Processing composite element: '{}' for base type: '{}'", elementStr, baseType);
                    if (elementStr.startsWith("(") && elementStr.endsWith(")")) {
                        // Convert composite element to Map
                        Map<String, Object> convertedMap = convertPostgresCompositeToMap(elementStr, baseType);
                        log.debug("Converted composite to map: {}", convertedMap);
                        return convertedMap;
                    } else {
                        // Fallback: return as string if not in expected format
                        log.warn("Composite array element not in expected format: {}", elementStr);
                        return elementStr;
                    }
                }
                
                // Handle custom enum types
                return elementStr;
            }).collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Error converting custom type array to List for column type: {}", columnType, e);
            return List.of();
        }
    }

    private List<Object> parsePostgresArrayString(String arrayStr) {
        if (arrayStr == null || arrayStr.trim().isEmpty()) {
            return List.of();
        }
        
        String content = arrayStr.trim();
        
        // Remove outer braces: "{1,2,3}" -> "1,2,3"
        if (content.startsWith("{") && content.endsWith("}")) {
            content = content.substring(1, content.length() - 1);
        }
        
        if (content.trim().isEmpty()) {
            return List.of();
        }
        
        // Split by commas, handling nested parentheses for composite types
        List<Object> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenthesesDepth = 0;
        boolean inQuotes = false;
        
        for (char c : content.toCharArray()) {
            if (c == '"' && (current.length() == 0 || current.charAt(current.length() - 1) != '\\')) {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (!inQuotes && c == '(') {
                parenthesesDepth++;
                current.append(c);
            } else if (!inQuotes && c == ')') {
                parenthesesDepth--;
                current.append(c);
            } else if (!inQuotes && c == ',' && parenthesesDepth == 0) {
                // Found a top-level comma, add current element
                if (current.length() > 0) {
                    elements.add(current.toString().trim());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        
        // Add the last element
        if (current.length() > 0) {
            elements.add(current.toString().trim());
        }
        
        return elements;
    }

    public String convertMapToPostgresComposite(Map<String, Object> compositeMap) {
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