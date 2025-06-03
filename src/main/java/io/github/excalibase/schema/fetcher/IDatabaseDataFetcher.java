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
package io.github.excalibase.schema.fetcher;

import graphql.schema.DataFetcher;

import java.util.List;
import java.util.Map;

/**
 * Interface for creating GraphQL data fetchers for database operations.
 * 
 * <p>This interface defines the contract for implementations that create GraphQL
 * data fetchers responsible for querying database tables and resolving relationships.
 * Data fetchers handle the actual database interactions and data transformation
 * required to fulfill GraphQL queries.</p>
 * 
 * <p>The interface provides methods for creating different types of data fetchers:</p>
 * <ul>
 *   <li>Table data fetchers for basic queries with offset-based pagination</li>
 *   <li>Connection data fetchers for cursor-based pagination (Relay specification)</li>
 *   <li>Relationship data fetchers for resolving foreign key associations</li>
 * </ul>
 * 
 * <p>Implementations should be database-specific and annotated with
 * {@link io.github.excalibase.annotation.ExcalibaseService} for proper service lookup.</p>
 *
 * @see graphql.schema.DataFetcher
 * @see io.github.excalibase.annotation.ExcalibaseService
 */
public interface IDatabaseDataFetcher {
    
    /**
     * Creates a data fetcher for querying a table with offset-based pagination.
     * 
     * <p>This method creates a data fetcher that handles basic table queries
     * with support for filtering, ordering, and offset-based pagination (limit/offset).
     * The fetcher should support common query parameters like orderBy, limit, and offset.</p>
     * 
     * @param tableName the name of the database table to query
     * @return a data fetcher that returns a list of records from the specified table
     */
    DataFetcher<List<Map<String, Object>>> createTableDataFetcher(String tableName);

    /**
     * Creates a connection data fetcher for cursor-based pagination.
     * 
     * <p>This method creates a data fetcher that implements the Relay Connection
     * Specification, providing cursor-based pagination with pageInfo, edges, and
     * node structures. The fetcher should support both forward and backward pagination
     * using first/after and last/before parameters.</p>
     * 
     * @param tableName the name of the database table to query
     * @return a data fetcher that returns a connection object with edges and pageInfo
     * @see <a href="https://relay.dev/graphql/connections.htm">Relay Connection Specification</a>
     */
    DataFetcher<Map<String, Object>> createConnectionDataFetcher(String tableName);

    /**
     * Creates a relationship data fetcher for resolving foreign key associations.
     * 
     * <p>This method creates a data fetcher that resolves relationships between
     * tables based on foreign key constraints. The fetcher handles the join logic
     * and returns the related records based on the foreign key relationship.</p>
     * 
     * @param tableName the name of the source table containing the foreign key
     * @param foreignKeyColumn the name of the foreign key column in the source table
     * @param referencedTable the name of the target table being referenced
     * @param referencedColumn the name of the column in the target table being referenced
     * @return a data fetcher that resolves the relationship and returns related records
     */
    DataFetcher<Map<String, Object>> createRelationshipDataFetcher(
            String tableName,
            String foreignKeyColumn,
            String referencedTable,
            String referencedColumn);
}
