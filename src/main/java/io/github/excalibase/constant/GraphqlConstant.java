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
}
