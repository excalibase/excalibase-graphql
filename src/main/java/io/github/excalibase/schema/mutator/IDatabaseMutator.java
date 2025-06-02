package io.github.excalibase.schema.mutator;

import graphql.schema.DataFetcher;

import java.util.List;
import java.util.Map;

public interface IDatabaseMutator {
    /**
     * Creates a DataFetcher for creating a single record in the specified table.
     * 
     * @param tableName The name of the table to create a record in
     * @return A DataFetcher that creates a record and returns the created record
     */
    DataFetcher<Map<String, Object>> createCreateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for updating a record in the specified table.
     * 
     * @param tableName The name of the table to update a record in
     * @return A DataFetcher that updates a record and returns the updated record
     */
    DataFetcher<Map<String, Object>> createUpdateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for deleting a record from the specified table.
     * 
     * @param tableName The name of the table to delete a record from
     * @return A DataFetcher that deletes a record and returns a boolean indicating success
     */
    DataFetcher<Boolean> createDeleteMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for bulk creating multiple records in the specified table.
     * 
     * @param tableName The name of the table to create records in
     * @return A DataFetcher that creates multiple records and returns the created records
     */
    DataFetcher<List<Map<String, Object>>> createBulkCreateMutationResolver(String tableName);

    /**
     * Creates a DataFetcher for creating a record with relationships in the specified table.
     * 
     * @param tableName The name of the table to create a record with relationships in
     * @return A DataFetcher that creates a record with relationships and returns the created record
     */
    DataFetcher<Map<String, Object>> createCreateWithRelationshipsMutationResolver(String tableName);
} 