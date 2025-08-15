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
package io.github.excalibase.postgres.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.postgres.reflector.PostgresDatabaseSchemaReflectorImplement;
import io.github.excalibase.postgres.service.CDCEvent;
import io.github.excalibase.postgres.service.CDCService;
import io.github.excalibase.schema.subscription.IDatabaseSubscription;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of IDatabaseSubscription.
 * 
 * <p>This implementation provides real-time subscription functionality for PostgreSQL databases
 * using Change Data Capture (CDC) through logical replication. It subscribes to actual database
 * changes and streams them to GraphQL clients via WebSocket subscriptions.</p>
 */
@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseSubscriptionImplement implements IDatabaseSubscription {
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseSubscriptionImplement.class);
    
    private final CDCService cdcService;
    private final ObjectMapper objectMapper;
    private final PostgresDatabaseSchemaReflectorImplement reflector;
    
    @Autowired
    public PostgresDatabaseSubscriptionImplement(CDCService cdcService, PostgresDatabaseSchemaReflectorImplement reflector) {
        this.cdcService = cdcService;
        this.reflector = reflector;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public DataFetcher<Publisher<Map<String, Object>>> createTableSubscriptionResolver(String tableName) {
        return (DataFetcher<Publisher<Map<String, Object>>>) environment -> {
            log.info("🔥 TABLE SUBSCRIPTION RESOLVER CALLED for table: {}", tableName);
            log.info("🔥 GraphQL Field: {}", environment.getField().getName());
            log.info("🔥 Arguments: {}", environment.getArguments());
            
            // Create a heartbeat stream to keep connection alive
            Flux<Map<String, Object>> heartbeatStream = Flux.interval(Duration.ofSeconds(30))
                    .map(tick -> {
                        Map<String, Object> heartbeatEvent = new HashMap<>();
                        heartbeatEvent.put("operation", "HEARTBEAT");
                        heartbeatEvent.put("table", tableName);
                        heartbeatEvent.put("schema", "public");
                        heartbeatEvent.put("timestamp", Instant.now().toString());
                        heartbeatEvent.put("lsn", null);
                        heartbeatEvent.put("error", null);
                        heartbeatEvent.put("data", null);
                        return heartbeatEvent;
                    });

            // Create the CDC stream and handle completion by recreating stream
            Flux<Map<String, Object>> cdcEventStream = cdcService.getTableEventStream(tableName)
                    .map(this::convertCDCEventToGraphQLEvent)
                    .doOnComplete(() -> {
                        log.warn("🔥 Table subscription: CDC table stream completed unexpectedly for {}", tableName);
                    })
                    .doOnError(error -> {
                        log.error("🔥 Table subscription: CDC stream error for {}: ", tableName, error);
                    })
                    // Restart the stream if it completes or errors
                    .repeatWhen(flux -> flux.delayElements(Duration.ofSeconds(1)))
                    .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(30)));

            // Merge CDC events with heartbeat to prevent completion
            return Flux.merge(cdcEventStream, heartbeatStream)
            .onErrorResume(error -> {
                log.error("Error in table subscription for {}: ", tableName, error);
                return Flux.just(createErrorEvent(tableName, error.getMessage()));
            })
            .doOnSubscribe(s -> log.info("Client subscribed to real-time changes for table: {}", tableName))
            .doOnCancel(() -> log.info("Client unsubscribed from real-time changes for table: {}", tableName))
            .doOnComplete(() -> log.debug("🔥 Table subscription: Combined stream completed for table: {}", tableName))
            .doOnError(error -> log.error("Subscription error for table {}: ", tableName, error));
        };
    }

    /**
     * Convert CDC event to GraphQL-compatible event format matching the schema
     */
    private Map<String, Object> convertCDCEventToGraphQLEvent(CDCEvent cdcEvent) {
        Map<String, Object> graphqlEvent = new HashMap<>();
        
        // Basic event metadata matching GraphQL schema
        graphqlEvent.put("table", cdcEvent.getTable());
        graphqlEvent.put("schema", cdcEvent.getSchema());
        graphqlEvent.put("operation", cdcEvent.getType().name());
        graphqlEvent.put("timestamp", Instant.ofEpochMilli(cdcEvent.getTimestamp()).toString());
        graphqlEvent.put("lsn", cdcEvent.getLsn() != null ? cdcEvent.getLsn().asString() : null);
        
        // Parse and structure data payload according to GraphQL schema
        Map<String, Object> dataPayload = parseDataPayload(cdcEvent, cdcEvent.getTable());
        graphqlEvent.put("data", dataPayload);
        
        // Error field (null if no error)
        graphqlEvent.put("error", null);
        
        return graphqlEvent;
    }
    
    /**
     * Parse CDC event data payload and structure it for GraphQL schema
     */
    private Map<String, Object> parseDataPayload(CDCEvent cdcEvent, String tableName) {
        try {
            if (cdcEvent.getData() == null || cdcEvent.getData().trim().isEmpty()) {
                return new HashMap<>();
            }
            
            log.debug("Parsing CDC data for table {}: {}", cdcEvent.getTable(), 
                     cdcEvent.getData().substring(0, Math.min(200, cdcEvent.getData().length())));
            
            @SuppressWarnings("unchecked")
            Map<String, Object> rawData = objectMapper.readValue(cdcEvent.getData(), Map.class);
            
            // Handle different CDC event types
            return switch (cdcEvent.getType()) {
                case INSERT, DELETE -> flattenRowData(rawData, tableName);
                case UPDATE -> handleUpdateData(rawData, tableName);
                default -> rawData;
            };
            
        } catch (Exception e) {
            log.warn("Failed to parse CDC event data as JSON for table {}: {}", 
                    cdcEvent.getTable(), e.getMessage());
            log.debug("Raw CDC data that failed to parse: {}", cdcEvent.getData());
            
            // Return error structure but still try to make it work
            Map<String, Object> errorData = new HashMap<>();
            errorData.put("parseError", "Failed to parse data as JSON: " + e.getMessage());
            errorData.put("rawData", cdcEvent.getData());
            errorData.put("table", cdcEvent.getTable());
            errorData.put("eventType", cdcEvent.getType().name());
            return errorData;
        }
    }
    
    /**
     * Flatten row data to match GraphQL field structure
     */
    private Map<String, Object> flattenRowData(Map<String, Object> rawData, String tableName) {
        Map<String, Object> flatData = new HashMap<>();
        TableInfo tableInfo = reflector.reflectSchema().getOrDefault(tableName, null);
        List<String> columns = tableInfo.getColumns().stream().map(ColumnInfo::getName).toList();
        // Copy all fields directly 
        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String key = entry.getKey();

            Object value = entry.getValue();

            // Handle nested objects if needed
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                flatData.putAll(nestedMap);
            } else {
                int index = Integer.parseInt(key.replace("col_", ""));
                String column = columns.get(index);
                flatData.put(column, value);
            }
        }
        
        return flatData;
    }
    
    /**
     * Handle UPDATE data which may have old/new structure
     */
    private Map<String, Object> handleUpdateData(Map<String, Object> rawData, String tableName) {
        Map<String, Object> updateData = new HashMap<>();
        
        // Check if we have old/new structure
        if (rawData.containsKey("old") && rawData.containsKey("new")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> oldData = (Map<String, Object>) rawData.get("old");
            @SuppressWarnings("unchecked")
            Map<String, Object> newData = (Map<String, Object>) rawData.get("new");
            
            updateData.put("old", flattenRowData(oldData, tableName));
            updateData.put("new", flattenRowData(newData, tableName));
            
            // Also include the new data directly for easier access
            updateData.putAll(flattenRowData(newData, tableName));
        } else {
            // Single data structure, treat as new data
            updateData.putAll(flattenRowData(rawData, tableName));
        }
        
        return updateData;
    }
    
    /**
     * Create error event for subscription errors
     */
    private Map<String, Object>  createErrorEvent(String tableName, String errorMessage) {
        Map<String, Object> errorEvent = new HashMap<>();
        errorEvent.put("table", tableName);
        errorEvent.put("operation", "ERROR");
        errorEvent.put("timestamp", Instant.now().toString());
        errorEvent.put("error", errorMessage);
        errorEvent.put("data", new HashMap<>());
        return errorEvent;
    }
}
