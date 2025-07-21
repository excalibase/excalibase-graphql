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

import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Custom GraphQL scalar type for PostgreSQL JSON and JSONB columns.
 * 
 * <p>This scalar handles JSON data by:</p>
 * <ul>
 *   <li>Accepting JSON strings as input</li>
 *   <li>Validating JSON syntax</li>
 *   <li>Returning parsed JSON objects for queries</li>
 *   <li>Supporting JSON path operations in filters</li>
 * </ul>
 */
public class JsonScalar {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static final GraphQLScalarType JSON = GraphQLScalarType.newScalar()
        .name("JSON")
        .description("A JSON scalar type that represents JSON values as strings")
        .coercing(new Coercing<Object, String>() {
            
            @Override
            public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
                if (dataFetcherResult == null) {
                    return null;
                }
                
                if (dataFetcherResult instanceof String) {
                    // Already a JSON string, validate and return
                    String jsonString = (String) dataFetcherResult;
                    try {
                        // Validate JSON syntax
                        objectMapper.readTree(jsonString);
                        return jsonString;
                    } catch (JsonProcessingException e) {
                        throw new CoercingSerializeException("Invalid JSON string: " + e.getMessage());
                    }
                }
                
                // Convert object to JSON string
                try {
                    return objectMapper.writeValueAsString(dataFetcherResult);
                } catch (JsonProcessingException e) {
                    throw new CoercingSerializeException("Unable to serialize object to JSON: " + e.getMessage());
                }
            }
            
            @Override
            public Object parseValue(Object input) throws CoercingParseValueException {
                if (input == null) {
                    return null;
                }
                
                if (input instanceof String) {
                    String jsonString = (String) input;
                    try {
                        // Parse and validate JSON, then return as JsonNode for further processing
                        JsonNode jsonNode = objectMapper.readTree(jsonString);
                        return jsonNode;
                    } catch (JsonProcessingException e) {
                        throw new CoercingParseValueException("Invalid JSON string: " + e.getMessage());
                    }
                }
                
                throw new CoercingParseValueException("Expected a JSON string but was: " + input.getClass().getSimpleName());
            }
            
            @Override
            public Object parseLiteral(Object input) throws CoercingParseLiteralException {
                if (input instanceof StringValue) {
                    String jsonString = ((StringValue) input).getValue();
                    try {
                        // Parse and validate JSON
                        JsonNode jsonNode = objectMapper.readTree(jsonString);
                        return jsonNode;
                    } catch (JsonProcessingException e) {
                        throw new CoercingParseLiteralException("Invalid JSON string: " + e.getMessage());
                    }
                }
                
                throw new CoercingParseLiteralException("Expected a StringValue but was: " + input.getClass().getSimpleName());
            }
        })
        .build();
    
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