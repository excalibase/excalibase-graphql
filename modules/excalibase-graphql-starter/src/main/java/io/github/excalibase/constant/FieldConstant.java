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
 * GraphQL field names and query parameter constants.
 */
public class FieldConstant {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private FieldConstant() {
    }

    // Pagination and ordering fields
    
    /** Field name for specifying ordering criteria in queries */
    public static final String ORDER_BY = "orderBy";
    
    /** Field name for limiting the number of results (offset-based pagination) */
    public static final String LIMIT = "limit";
    
    /** Field name for specifying result offset (offset-based pagination) */
    public static final String OFFSET = "offset";
    
    /** Field name for the edges array in connection types */
    public static final String EDGES = "edges";
    
    /** Field name indicating if there are more results after the current page */
    public static final String HAS_NEXT_PAGE = "hasNextPage";
    
    /** Field name indicating if there are results before the current page */
    public static final String HAS_PREVIOUS_PAGE = "hasPreviousPage";
    
    /** Field name for page information in connection types */
    public static final String PAGE_INFO = "pageInfo";
    
    /** Field name for the cursor of the first result in the current page */
    public static final String START_CURSOR = "startCursor";
    
    /** Field name for the cursor of the last result in the current page */
    public static final String END_CURSOR = "endCursor";
    
    /** Field name for the total count of available results */
    public static final String TOTAL_COUNT = "totalCount";
    
    /** Field name for error information */
    public static final String ERROR = "error";
    
    /** Field name for the actual data node in an edge */
    public static final String NODE = "node";
    
    /** Field name for cursor information in edges */
    public static final String CURSOR = "cursor";
    
    /** Field name for forward pagination limit (cursor-based) */
    public static final String FIRST = "first";
    
    /** Field name for backward pagination limit (cursor-based) */
    public static final String LAST = "last";
    
    /** Field name for cursor-based pagination - results before this cursor */
    public static final String BEFORE = "before";
    
    /** Field name for cursor-based pagination - results after this cursor */
    public static final String AFTER = "after";

    // Query operators for filtering
    
    /** Operator for equality comparison */
    public static final String OPERATOR_EQ = "eq";
    
    /** Operator for inequality comparison */
    public static final String OPERATOR_NEQ = "neq";
    
    /** Operator for string containment filtering */
    public static final String OPERATOR_CONTAINS = "contains";
    
    /** Operator for string prefix filtering */
    public static final String OPERATOR_STARTS_WITH = "startsWith";
    
    /** Operator for string suffix filtering */
    public static final String OPERATOR_ENDS_WITH = "endsWith";
    
    /** Operator for SQL LIKE pattern matching */
    public static final String OPERATOR_LIKE = "like";
    
    /** Operator for case-insensitive SQL LIKE pattern matching */
    public static final String OPERATOR_ILIKE = "ilike";
    
    /** Operator for greater than comparison */
    public static final String OPERATOR_GT = "gt";
    
    /** Operator for greater than or equal comparison */
    public static final String OPERATOR_GTE = "gte";
    
    /** Operator for less than comparison */
    public static final String OPERATOR_LT = "lt";
    
    /** Operator for less than or equal comparison */
    public static final String OPERATOR_LTE = "lte";
    
    /** Operator for null value checking */
    public static final String OPERATOR_IS_NULL = "isNull";
    
    /** Operator for non-null value checking */
    public static final String OPERATOR_IS_NOT_NULL = "isNotNull";
    
    /** Operator for checking if value is in a list */
    public static final String OPERATOR_IN = "in";
    
    /** Operator for checking if value is not in a list */
    public static final String OPERATOR_NOT_IN = "notIn";

    // Context keys
    
    /** Context key for storing batch loading context information */
    public static final String BATCH_CONTEXT = "BATCH_CONTEXT";
}
