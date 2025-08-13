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

import graphql.schema.DataFetcher;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.schema.subscription.IDatabaseSubscription;
import io.github.excalibase.service.ServiceLookup;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL implementation of IDatabaseSubscription.
 * 
 * <p>This implementation provides basic subscription functionality for PostgreSQL databases.
 * Currently, returns placeholder data for table subscriptions - real-time change detection
 * will be implemented later using PostgreSQL NOTIFY/LISTEN or logical replication.</p>
 */
@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseSubscriptionImplement implements IDatabaseSubscription {
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseSubscriptionImplement.class);

    @Override
    public DataFetcher<Publisher<Map<String, Object>>> createTableSubscriptionResolver(String tableName) {
        return (DataFetcher<Publisher<Map<String, Object>>>) environment -> {
            log.debug("Creating table subscription for table: {}", tableName);
            
            // For now, return a simple periodic update with placeholder data
            // TODO: Implement real-time change detection using PostgreSQL NOTIFY/LISTEN
            return Flux.interval(Duration.ofSeconds(1))
                    .map(i -> {
                        Map<String, Object> change = new HashMap<>();
                        change.put("table", tableName);
                        change.put("operation", "UPDATE"); // INSERT, UPDATE, DELETE
                        change.put("timestamp", Instant.now().toString());
                        change.put("data", createPlaceholderData(tableName, i));
                        return change;
                    })
                    .doOnSubscribe(s -> log.info("Client subscribed to table: {}", tableName))
                    .doOnCancel(() -> log.info("Client unsubscribed from table: {}", tableName));
        };
    }

    @Override
    public DataFetcher<Publisher<String>> createHealthSubscriptionResolver() {
        return (DataFetcher<Publisher<String>>) environment -> {
            log.debug("Creating health subscription");
            
            return Flux.interval(Duration.ofMillis(250))
                    .map(i -> "OK - heartbeat " + Instant.now())
                    .doOnSubscribe(s -> log.info("Client subscribed to health check"))
                    .doOnCancel(() -> log.info("Client unsubscribed from health check"));
        };
    }

    /**
     * Creates placeholder data for table subscriptions.
     * This will be replaced with actual table data when real-time detection is implemented.
     */
    private Map<String, Object> createPlaceholderData(String tableName, long sequenceNumber) {
        Map<String, Object> data = new HashMap<>();
        
        // Add some generic fields that most tables might have
        data.put("id", sequenceNumber);
        data.put("sequence", sequenceNumber);
        data.put("table_name", tableName);
        data.put("last_updated", Instant.now().toString());
        
        // Add table-specific placeholder data based on common table names
        switch (tableName.toLowerCase()) {
            case "customer":
                data.put("customer_id", sequenceNumber);
                data.put("first_name", "Customer" + sequenceNumber);
                data.put("last_name", "LastName" + sequenceNumber);
                break;
            case "order":
            case "orders":
                data.put("order_id", sequenceNumber);
                data.put("total_amount", 100.0 + sequenceNumber);
                data.put("status", "PENDING");
                break;
            case "product":
            case "products":
                data.put("product_id", sequenceNumber);
                data.put("product_name", "Product" + sequenceNumber);
                data.put("price", 50.0 + sequenceNumber);
                break;
            default:
                // Generic placeholder for unknown tables
                data.put("name", tableName + "_" + sequenceNumber);
                data.put("value", "sample_value_" + sequenceNumber);
                break;
        }
        
        return data;
    }
}
