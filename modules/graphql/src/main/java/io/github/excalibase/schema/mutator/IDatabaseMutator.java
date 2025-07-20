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
package io.github.excalibase.schema.mutator;

import graphql.schema.DataFetcher;

import java.util.List;
import java.util.Map;

/**
 * Interface for creating GraphQL mutation resolvers for database operations.
 * 
 * <p>This interface defines the contract for implementations that create GraphQL
 * mutation resolvers responsible for performing create, update, and delete operations
 * on database tables. Mutation resolvers handle the actual database transactions
 * and data modifications required to fulfill GraphQL mutations.</p>
 * 
 * <p>The interface provides methods for creating different types of mutation resolvers:</p>
 * <ul>
 *   <li>Create mutations for inserting new records</li>
 *   <li>Update mutations for modifying existing records</li>
 *   <li>Delete mutations for removing records</li>
 *   <li>Bulk create mutations for inserting multiple records</li>
 *   <li>Create with relationships mutations for complex insertions</li>
 * </ul>
 * 
 * <p>Implementations should be database-specific and annotated with
 * {@link io.github.excalibase.annotation.ExcalibaseService} for proper service lookup.</p>
 *
 * @see graphql.schema.DataFetcher
 * @see io.github.excalibase.annotation.ExcalibaseService
 */
public interface IDatabaseMutator {
    /**
     * Creates a DataFetcher for creating a single record in the specified table.
     * 
     * <p>This method creates a mutation resolver that handles single record creation.
     * The resolver should validate input data, execute the insertion, and return
     * the created record with all generated fields (like auto-incrementing IDs).</p>
     * 
     * @param tableName The name of the table to create a record in
     * @return A DataFetcher that creates a record and returns the created record
     * @throws io.github.excalibase.exception.DataMutationException if the creation fails
     */
    DataFetcher<Map<String, Object>> createCreateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for updating a record in the specified table.
     * 
     * <p>This method creates a mutation resolver that handles record updates.
     * The resolver should locate the record by ID, apply the specified changes,
     * and return the updated record with all current field values.</p>
     * 
     * @param tableName The name of the table to update a record in
     * @return A DataFetcher that updates a record and returns the updated record
     * @throws io.github.excalibase.exception.NotFoundException if the record to update is not found
     * @throws io.github.excalibase.exception.DataMutationException if the update fails
     */
    DataFetcher<Map<String, Object>> createUpdateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for deleting a record from the specified table.
     * 
     * <p>This method creates a mutation resolver that handles record deletion.
     * The resolver should locate the record by ID, delete it from the database,
     * and return a boolean indicating the success of the operation.</p>
     * 
     * @param tableName The name of the table to delete a record from
     * @return A DataFetcher that deletes a record and returns a boolean indicating success
     * @throws io.github.excalibase.exception.NotFoundException if the record to delete is not found
     * @throws io.github.excalibase.exception.DataMutationException if the deletion fails
     */
    DataFetcher<Boolean> createDeleteMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for bulk creating multiple records in the specified table.
     * 
     * <p>This method creates a mutation resolver that handles batch record creation.
     * The resolver should process multiple input records in a single transaction,
     * perform bulk insertion, and return all created records with generated fields.</p>
     * 
     * @param tableName The name of the table to create records in
     * @return A DataFetcher that creates multiple records and returns the created records
     * @throws io.github.excalibase.exception.DataMutationException if the bulk creation fails
     */
    DataFetcher<List<Map<String, Object>>> createBulkCreateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for creating a record with relationships in the specified table.
     * 
     * <p>This method creates a mutation resolver that handles complex record creation
     * involving foreign key relationships. The resolver should manage related record
     * creation or linking, ensure referential integrity, and return the created record
     * with all relationship data populated.</p>
     * 
     * @param tableName The name of the table to create a record with relationships in
     * @return A DataFetcher that creates a record with relationships and returns the created record
     * @throws io.github.excalibase.exception.DataMutationException if the creation with relationships fails
     */
    DataFetcher<Map<String, Object>> createCreateWithRelationshipsMutationResolver(String tableName);
} 