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
import io.github.excalibase.model.CDCEvent;
import io.github.excalibase.schema.subscription.IDatabaseSubscription;
import io.github.excalibase.service.NatsCDCService;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL implementation of IDatabaseSubscription.
 * <p>
 * Subscribes to CDC events from excalibase-watcher via NATS JetStream.
 * The watcher sends real column names in the data payload, so no col_0 mapping is needed.
 * </p>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.POSTGRES)
public class PostgresDatabaseSubscriptionImplement implements IDatabaseSubscription {

    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseSubscriptionImplement.class);

    private final NatsCDCService natsCDCService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostgresDatabaseSubscriptionImplement(NatsCDCService natsCDCService) {
        this.natsCDCService = natsCDCService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public DataFetcher<Publisher<Map<String, Object>>> buildTableSubscriptionResolver(String tableName) {
        return environment -> {
            log.info("Postgres subscription resolver called for table: {}", tableName);

            // Heartbeat stream to keep WebSocket alive
            Flux<Map<String, Object>> heartbeatStream = Flux.interval(Duration.ofSeconds(30))
                    .map(tick -> {
                        Map<String, Object> event = new HashMap<>();
                        event.put("operation", "HEARTBEAT");
                        event.put("table", tableName);
                        event.put("schema", "public");
                        event.put("timestamp", Instant.now().toString());
                        event.put("error", null);
                        event.put("data", null);
                        return event;
                    });

            // CDC event stream from NATS
            Flux<Map<String, Object>> cdcEventStream = natsCDCService.getTableEventStream(tableName)
                    .map(this::toGraphQLEvent)
                    .repeatWhen(flux -> flux.delayElements(Duration.ofSeconds(1)))
                    .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(30)));

            return Flux.merge(cdcEventStream, heartbeatStream)
                    .onErrorResume(error -> {
                        log.error("Error in subscription for {}: ", tableName, error);
                        return Flux.just(createErrorEvent(tableName, error.getMessage()));
                    })
                    .doOnSubscribe(s -> log.info("Client subscribed to table: {}", tableName))
                    .doOnCancel(() -> log.info("Client unsubscribed from table: {}", tableName));
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toGraphQLEvent(CDCEvent event) {
        Map<String, Object> graphqlEvent = new HashMap<>();
        graphqlEvent.put("table", event.getTable());
        graphqlEvent.put("schema", event.getSchema());
        graphqlEvent.put("operation", event.getType().name());
        graphqlEvent.put("timestamp", Instant.ofEpochMilli(event.getTimestamp()).toString());
        graphqlEvent.put("error", null);

        // Watcher sends real column names — parse data JSON directly
        Map<String, Object> dataPayload = new HashMap<>();
        if (event.getData() != null && !event.getData().isBlank()) {
            try {
                dataPayload = objectMapper.readValue(event.getData(), Map.class);
            } catch (Exception e) {
                log.warn("Failed to parse CDC data for table {}: {}", event.getTable(), e.getMessage());
            }
        }
        graphqlEvent.put("data", dataPayload);

        return graphqlEvent;
    }

    private Map<String, Object> createErrorEvent(String tableName, String errorMessage) {
        Map<String, Object> event = new HashMap<>();
        event.put("table", tableName);
        event.put("operation", "ERROR");
        event.put("timestamp", Instant.now().toString());
        event.put("error", errorMessage);
        event.put("data", new HashMap<>());
        return event;
    }
}
