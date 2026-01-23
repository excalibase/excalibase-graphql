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
package io.github.excalibase.schema.subscription;

import graphql.schema.DataFetcher;
import org.reactivestreams.Publisher;

import java.util.Map;

/**
 * Interface for creating GraphQL subscription resolvers for database operations.
 * 
 * <p>This interface defines the contract for implementations that create GraphQL
 * subscription resolvers responsible for providing real-time updates when database
 * table data changes. Subscription resolvers handle streaming data changes and
 * notifications for table modifications.</p>
 * 
 * <p>The interface provides methods for creating different types of subscription resolvers:</p>
 * <ul>
 *   <li>Table subscriptions for listening to table data changes</li>
 *   <li>Health subscriptions for monitoring system status</li>
 * </ul>
 * 
 * <p>Implementations should be database-specific and annotated with
 * {@link io.github.excalibase.annotation.ExcalibaseService} for proper service lookup.</p>
 *
 * @see DataFetcher
 * @see Publisher
 * @see io.github.excalibase.annotation.ExcalibaseService
 */
public interface IDatabaseSubscription {
    
    /**
     * Creates a DataFetcher for subscribing to table data changes.
     * 
     * <p>This method creates a subscription resolver that handles real-time notifications
     * when data in the specified table changes. The resolver should emit updates when
     * records are inserted, updated, or deleted in the table.</p>
     * 
     * @param tableName The name of the table to subscribe to changes for
     * @return A DataFetcher that returns a Publisher stream of table data changes
     */
    DataFetcher<Publisher<Map<String, Object>>> buildTableSubscriptionResolver(String tableName);
}
