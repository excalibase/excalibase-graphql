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
package io.github.excalibase.dataloader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native DataLoader implementation for Excalibase.
 *
 * <p>Provides request-scoped batching and caching for database queries,
 * preventing N+1 query problems in GraphQL resolvers.</p>
 *
 * <p><b>Key Features:</b></p>
 * <ul>
 *   <li>Automatic batching of queries by table and key column</li>
 *   <li>Request-scoped caching (prevents duplicate fetches within same request)</li>
 *   <li>Multi-level relationship support (recursive batching)</li>
 *   <li>Thread-safe per-request context</li>
 * </ul>
 *
 * <p><b>Usage Example:</b></p>
 * <pre>
 * ExcalibaseBatchLoader loader = new ExcalibaseBatchLoader();
 *
 * // Queue loads
 * loader.queueLoad("customer", "customer_id", 1);
 * loader.queueLoad("customer", "customer_id", 2);
 * loader.queueLoad("customer", "customer_id", 3);
 *
 * // Execute batch (returns all queued IDs for this table)
 * Set&lt;Object&gt; idsToFetch = loader.getQueuedIds("customer", "customer_id");
 *
 * // Store results
 * loader.cacheResults("customer", "customer_id", fetchedRecords);
 *
 * // Retrieve from cache
 * Map&lt;String, Object&gt; customer = loader.getCached("customer", "customer_id", 1);
 * </pre>
 *
 * @see graphql.schema.DataFetchingEnvironment
 */
public class ExcalibaseBatchLoader {

    /**
     * Request-scoped cache: table -> keyColumn -> keyValue -> record
     */
    private final Map<String, Map<String, Map<Object, Map<String, Object>>>> cache = new ConcurrentHashMap<>();

    /**
     * Pending loads queue: table -> keyColumn -> Set of IDs to load
     */
    private final Map<String, Map<String, Set<Object>>> pendingLoads = new ConcurrentHashMap<>();

    /**
     * Tracks processed tables to prevent infinite recursion
     */
    private final Set<String> processedTables = ConcurrentHashMap.newKeySet();

    /**
     * Queues a load operation for later batching.
     *
     * @param tableName the table to load from
     * @param keyColumn the key column to match on
     * @param keyValue the key value to load
     */
    public void queueLoad(String tableName, String keyColumn, Object keyValue) {
        if (keyValue == null) {
            return;
        }

        pendingLoads
            .computeIfAbsent(tableName, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(keyColumn, k -> ConcurrentHashMap.newKeySet())
            .add(keyValue);
    }

    /**
     * Queues multiple load operations for later batching.
     *
     * @param tableName the table to load from
     * @param keyColumn the key column to match on
     * @param keyValues the key values to load
     */
    public void queueLoads(String tableName, String keyColumn, Set<Object> keyValues) {
        if (keyValues == null || keyValues.isEmpty()) {
            return;
        }

        pendingLoads
            .computeIfAbsent(tableName, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(keyColumn, k -> ConcurrentHashMap.newKeySet())
            .addAll(keyValues);
    }

    /**
     * Gets all queued IDs for a specific table and key column, then clears the queue.
     *
     * @param tableName the table name
     * @param keyColumn the key column name
     * @return set of IDs that need to be fetched
     */
    public Set<Object> getQueuedIds(String tableName, String keyColumn) {
        Map<String, Set<Object>> tableLoads = pendingLoads.get(tableName);
        if (tableLoads == null) {
            return Set.of();
        }

        Set<Object> ids = tableLoads.remove(keyColumn);
        if (ids == null) {
            return Set.of();
        }

        // Filter out IDs that are already cached
        Map<String, Map<Object, Map<String, Object>>> tableCache = cache.get(tableName);
        if (tableCache != null) {
            Map<Object, Map<String, Object>> columnCache = tableCache.get(keyColumn);
            if (columnCache != null) {
                Set<Object> uncached = new HashSet<>(ids);
                uncached.removeAll(columnCache.keySet());
                return uncached;
            }
        }

        return ids;
    }

    /**
     * Checks if there are pending loads for a table.
     *
     * @param tableName the table name
     * @return true if there are pending loads
     */
    public boolean hasPendingLoads(String tableName) {
        Map<String, Set<Object>> tableLoads = pendingLoads.get(tableName);
        return tableLoads != null && !tableLoads.isEmpty();
    }

    /**
     * Caches loaded results for later retrieval.
     *
     * @param tableName the table name
     * @param keyColumn the key column name
     * @param records the loaded records
     */
    public void cacheResults(String tableName, String keyColumn, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        Map<Object, Map<String, Object>> columnCache = cache
            .computeIfAbsent(tableName, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(keyColumn, k -> new ConcurrentHashMap<>());

        for (Map<String, Object> record : records) {
            Object keyValue = record.get(keyColumn);
            if (keyValue != null) {
                columnCache.put(keyValue, record);
            }
        }
    }

    /**
     * Retrieves a cached record.
     *
     * @param tableName the table name
     * @param keyColumn the key column name
     * @param keyValue the key value
     * @return the cached record, or null if not found
     */
    public Map<String, Object> getCached(String tableName, String keyColumn, Object keyValue) {
        if (keyValue == null) {
            return null;
        }

        Map<String, Map<Object, Map<String, Object>>> tableCache = cache.get(tableName);
        if (tableCache == null) {
            return null;
        }

        Map<Object, Map<String, Object>> columnCache = tableCache.get(keyColumn);
        if (columnCache == null) {
            return null;
        }

        return columnCache.get(keyValue);
    }

    /**
     * Retrieves all cached records for a table and key column.
     *
     * @param tableName the table name
     * @param keyColumn the key column name
     * @return map of keyValue -> record
     */
    public Map<Object, Map<String, Object>> getAllCached(String tableName, String keyColumn) {
        Map<String, Map<Object, Map<String, Object>>> tableCache = cache.get(tableName);
        if (tableCache == null) {
            return Map.of();
        }

        Map<Object, Map<String, Object>> columnCache = tableCache.get(keyColumn);
        return columnCache != null ? new HashMap<>(columnCache) : Map.of();
    }

    /**
     * Marks a table as processed to prevent infinite recursion.
     *
     * @param tableName the table name
     * @return true if this is the first time marking this table
     */
    public boolean markProcessed(String tableName) {
        return processedTables.add(tableName);
    }

    /**
     * Checks if a table has been processed.
     *
     * @param tableName the table name
     * @return true if already processed
     */
    public boolean isProcessed(String tableName) {
        return processedTables.contains(tableName);
    }

    /**
     * Clears all caches and queues. Call this at the end of each GraphQL request.
     */
    public void clear() {
        cache.clear();
        pendingLoads.clear();
        processedTables.clear();
    }

    /**
     * Gets statistics about the current batch loader state.
     *
     * @return statistics string
     */
    public String getStats() {
        int totalCached = cache.values().stream()
            .mapToInt(tableCache -> tableCache.values().stream()
                .mapToInt(Map::size)
                .sum())
            .sum();

        int totalPending = pendingLoads.values().stream()
            .mapToInt(tableLoads -> tableLoads.values().stream()
                .mapToInt(Set::size)
                .sum())
            .sum();

        return String.format("BatchLoader Stats: cached=%d, pending=%d, processed_tables=%d",
            totalCached, totalPending, processedTables.size());
    }
}
