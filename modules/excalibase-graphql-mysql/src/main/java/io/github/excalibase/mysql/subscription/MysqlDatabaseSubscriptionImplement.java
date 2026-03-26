package io.github.excalibase.mysql.subscription;

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
 * MySQL implementation of {@link IDatabaseSubscription}.
 * <p>
 * Subscribes to CDC events from excalibase-watcher via NATS JetStream.
 * The watcher handles MySQL binlog replication and publishes events with real column names.
 * </p>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlDatabaseSubscriptionImplement implements IDatabaseSubscription {

    private static final Logger log = LoggerFactory.getLogger(MysqlDatabaseSubscriptionImplement.class);

    private final NatsCDCService natsCDCService;
    private final ObjectMapper objectMapper;

    @Autowired
    public MysqlDatabaseSubscriptionImplement(NatsCDCService natsCDCService) {
        this.natsCDCService = natsCDCService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public DataFetcher<Publisher<Map<String, Object>>> buildTableSubscriptionResolver(String tableName) {
        return env -> {
            log.info("MySQL subscription resolver called for table: {}", tableName);

            // Heartbeat stream to keep WebSocket alive
            Flux<Map<String, Object>> heartbeatStream = Flux.interval(Duration.ofSeconds(30))
                    .map(tick -> {
                        Map<String, Object> event = new HashMap<>();
                        event.put("operation", "HEARTBEAT");
                        event.put("table", tableName);
                        event.put("timestamp", Instant.now().toString());
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
                        Map<String, Object> errorEvent = new HashMap<>();
                        errorEvent.put("table", tableName);
                        errorEvent.put("operation", "ERROR");
                        errorEvent.put("timestamp", Instant.now().toString());
                        errorEvent.put("error", error.getMessage());
                        errorEvent.put("data", new HashMap<>());
                        return Flux.just(errorEvent);
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
}
