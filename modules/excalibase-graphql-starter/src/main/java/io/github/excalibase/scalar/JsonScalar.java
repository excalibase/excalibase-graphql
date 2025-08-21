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
package io.github.excalibase.scalar;

import graphql.language.*;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Custom GraphQL scalar type for PostgreSQL JSON and JSONB columns.
 * 
 * <p>This scalar handles JSON data by:</p>
 * <ul>
 *   <li>Accepting JSON strings as input</li>
 *   <li>Accepting direct GraphQL objects (Maps), arrays (Lists), and primitives</li>
 *   <li>Validating JSON syntax</li>
 *   <li>Returning parsed JSON objects for queries</li>
 *   <li>Supporting JSON path operations in filters</li>
 * </ul>
 */
public class JsonScalar {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static final GraphQLScalarType JSON = GraphQLScalarType.newScalar()
        .name("JSON")
        .description("A JSON scalar type that accepts JSON strings, objects, arrays, and primitives")
        .coercing(new Coercing<Object, String>() {
            
            @Override
            public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
                if (dataFetcherResult == null) {
                    return null;
                }
                
                try {
                    // If it's already a JsonNode (from our parsing), convert to string
                    if (dataFetcherResult instanceof JsonNode) {
                        return objectMapper.writeValueAsString(dataFetcherResult);
                    }
                    
                    // If it's a string, validate it's valid JSON and return
                    if (dataFetcherResult instanceof String) {
                        String jsonString = (String) dataFetcherResult;
                        // Validate JSON syntax
                        objectMapper.readTree(jsonString);
                        return jsonString;
                    }
                    
                    // For Maps, Lists, and primitives, convert to JSON string
                    if (dataFetcherResult instanceof Map || 
                        dataFetcherResult instanceof List ||
                        dataFetcherResult instanceof Number ||
                        dataFetcherResult instanceof Boolean) {
                        return objectMapper.writeValueAsString(dataFetcherResult);
                    }
                    
                    // Fallback: convert any other object to JSON string
                    return objectMapper.writeValueAsString(dataFetcherResult);
                    
                } catch (JsonProcessingException e) {
                    throw new CoercingSerializeException("Unable to serialize to JSON: " + e.getMessage());
                }
            }
            
            @Override
            public Object parseValue(Object input) throws CoercingParseValueException {
                if (input == null) {
                    return null;
                }
                
                try {
                    // Accept direct Maps (GraphQL objects)
                    if (input instanceof Map) {
                        return objectMapper.convertValue(input, JsonNode.class);
                    }
                    
                    // Accept direct Lists (GraphQL arrays)
                    if (input instanceof List) {
                        return objectMapper.convertValue(input, JsonNode.class);
                    }
                    
                    // Accept primitives (Numbers, Booleans)
                    if (input instanceof Number || input instanceof Boolean) {
                        return objectMapper.valueToTree(input);
                    }
                    
                    // Keep existing string parsing for backward compatibility
                    if (input instanceof String) {
                        String jsonString = (String) input;
                        // Parse and validate JSON, then return as JsonNode for further processing
                        JsonNode jsonNode = objectMapper.readTree(jsonString);
                        return jsonNode;
                    }
                    
                    throw new CoercingParseValueException(
                        "Expected a JSON value (object, array, string, number, or boolean) but was: " + 
                        input.getClass().getSimpleName());
                        
                } catch (JsonProcessingException e) {
                    throw new CoercingParseValueException("Invalid JSON input: " + e.getMessage());
                } catch (IllegalArgumentException e) {
                    throw new CoercingParseValueException("Cannot convert input to JSON: " + e.getMessage());
                }
            }
            
            @Override
            public Object parseLiteral(Object input) throws CoercingParseLiteralException {
                if (input == null) {
                    return null;
                }
                
                try {
                    // Handle object literals
                    if (input instanceof ObjectValue) {
                        return convertObjectLiteralToJsonNode((ObjectValue) input);
                    }
                    
                    // Handle array literals
                    if (input instanceof ArrayValue) {
                        return convertArrayLiteralToJsonNode((ArrayValue) input);
                    }
                    
                    // Handle primitive literals
                    if (input instanceof IntValue) {
                        return objectMapper.valueToTree(((IntValue) input).getValue());
                    }
                    
                    if (input instanceof FloatValue) {
                        return objectMapper.valueToTree(((FloatValue) input).getValue());
                    }
                    
                    if (input instanceof BooleanValue) {
                        return objectMapper.valueToTree(((BooleanValue) input).isValue());
                    }
                    
                    // Keep existing string handling for backward compatibility
                    if (input instanceof StringValue) {
                        String jsonString = ((StringValue) input).getValue();
                        // Try to parse as JSON first, if it fails treat as plain string
                        try {
                            JsonNode jsonNode = objectMapper.readTree(jsonString);
                            return jsonNode;
                        } catch (JsonProcessingException e) {
                            // If not valid JSON, treat as plain string value
                            return objectMapper.valueToTree(jsonString);
                        }
                    }
                    
                    throw new CoercingParseLiteralException(
                        "Expected a JSON literal (object, array, string, number, or boolean) but was: " + 
                        input.getClass().getSimpleName());
                        
                } catch (Exception e) {
                    throw new CoercingParseLiteralException("Cannot convert literal to JSON: " + e.getMessage());
                }
            }
        })
        .build();
        
    /**
     * Converts a GraphQL ObjectValue literal to JsonNode.
     */
    private static JsonNode convertObjectLiteralToJsonNode(ObjectValue objectValue) {
        Map<String, Object> map = new java.util.HashMap<>();
        for (ObjectField field : objectValue.getObjectFields()) {
            String fieldName = field.getName();
            Object fieldValue = convertValueLiteralToJava(field.getValue());
            map.put(fieldName, fieldValue);
        }
        return objectMapper.convertValue(map, JsonNode.class);
    }
    
    /**
     * Converts a GraphQL ArrayValue literal to JsonNode.
     */
    private static JsonNode convertArrayLiteralToJsonNode(ArrayValue arrayValue) {
        List<Object> list = new java.util.ArrayList<>();
        for (Value<?> value : arrayValue.getValues()) {
            Object javaValue = convertValueLiteralToJava(value);
            list.add(javaValue);
        }
        return objectMapper.convertValue(list, JsonNode.class);
    }
    
    /**
     * Converts any GraphQL Value literal to a Java object.
     */
    private static Object convertValueLiteralToJava(Value<?> value) {
        if (value instanceof StringValue) {
            return ((StringValue) value).getValue();
        } else if (value instanceof IntValue) {
            return ((IntValue) value).getValue();
        } else if (value instanceof FloatValue) {
            return ((FloatValue) value).getValue();
        } else if (value instanceof BooleanValue) {
            return ((BooleanValue) value).isValue();
        } else if (value instanceof ObjectValue) {
            Map<String, Object> map = new java.util.HashMap<>();
            for (ObjectField field : ((ObjectValue) value).getObjectFields()) {
                map.put(field.getName(), convertValueLiteralToJava(field.getValue()));
            }
            return map;
        } else if (value instanceof ArrayValue) {
            List<Object> list = new java.util.ArrayList<>();
            for (Value<?> arrayElement : ((ArrayValue) value).getValues()) {
                list.add(convertValueLiteralToJava(arrayElement));
            }
            return list;
        } else if (value instanceof NullValue) {
            return null;
        } else {
            // Fallback: convert to string
            return value.toString();
        }
    }
    
    /**
     * Validates if a string is valid JSON.
     * 
     * @param jsonString the string to validate
     * @return true if valid JSON, false otherwise
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return false;
        }
        
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
    
    /**
     * Parses a JSON string to JsonNode.
     * 
     * @param jsonString the JSON string to parse
     * @return JsonNode representation
     * @throws JsonProcessingException if parsing fails
     */
    public static JsonNode parseJson(String jsonString) throws JsonProcessingException {
        return objectMapper.readTree(jsonString);
    }
    
    /**
     * Converts an object to JSON string.
     * 
     * @param object the object to convert
     * @return JSON string representation
     * @throws JsonProcessingException if conversion fails
     */
    public static String toJsonString(Object object) throws JsonProcessingException {
        return objectMapper.writeValueAsString(object);
    }
} 