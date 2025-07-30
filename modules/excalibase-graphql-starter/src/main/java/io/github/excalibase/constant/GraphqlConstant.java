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
package io.github.excalibase.constant;

/**
 * GraphQL schema generation constants.
 */
public class GraphqlConstant {
    public static final String QUERY = "Query";
    public static final String MUTATION = "Mutation";
    public static final String SUBSCRIPTION = "Subscription";
    
    /**
     * Suffix used for connection-based query fields.
     * 
     * <p>This suffix is appended to table names to create connection fields that
     * follow the Relay Connection Specification for cursor-based pagination.
     * For example, a "users" table would have both "users" (simple pagination)
     * and "usersConnection" (cursor-based pagination) query fields.</p>
     * 
     * @see <a href="https://relay.dev/graphql/connections.htm">Relay Connection Specification</a>
     */
    public static final String CONNECTION_SUFFIX = "Connection";
    
    // Common GraphQL field names
    
    /** Health check field name */
    public static final String HEALTH = "health";
    
    /** Where clause field name */
    public static final String WHERE = "where";
    
    /** OR clause field name */
    public static final String OR = "or";
    
    /** Input field name for various operations */
    public static final String INPUT = "input";
    
    /** Inputs field name for bulk operations */
    public static final String INPUTS = "inputs";
    
    /** ID field name */
    public static final String ID = "id";
    
    // GraphQL enum types
    
    /** OrderDirection enum type name */
    public static final String ORDER_DIRECTION = "OrderDirection";
    
    /** Ascending order direction value */
    public static final String ASC = "ASC";
    
    /** Descending order direction value */
    public static final String DESC = "DESC";
    
    // GraphQL type suffixes
    
    /** Edge type suffix for Relay connections */
    public static final String EDGE_SUFFIX = "Edge";
    
    /** OrderByInput type suffix */
    public static final String ORDER_BY_INPUT_SUFFIX = "OrderByInput";
    
    /** CreateInput type suffix */
    public static final String CREATE_INPUT_SUFFIX = "CreateInput";
    
    /** UpdateInput type suffix */
    public static final String UPDATE_INPUT_SUFFIX = "UpdateInput";
    
    /** RelationshipInput type suffix */
    public static final String RELATIONSHIP_INPUT_SUFFIX = "RelationshipInput";
    
    /** ConnectInput type suffix */
    public static final String CONNECT_INPUT_SUFFIX = "ConnectInput";
    
    // Filter type names
    
    /** String filter type name */
    public static final String STRING_FILTER = "StringFilter";
    
    /** Integer filter type name */
    public static final String INT_FILTER = "IntFilter";
    
    /** Float filter type name */
    public static final String FLOAT_FILTER = "FloatFilter";
    
    /** Boolean filter type name */
    public static final String BOOLEAN_FILTER = "BooleanFilter";
    
    /** JSON filter type name */
    public static final String JSON_FILTER = "JsonFilter";
}
