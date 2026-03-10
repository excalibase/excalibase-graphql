package io.github.excalibase.mysql.subscription;

import graphql.schema.DataFetcher;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.schema.subscription.IDatabaseSubscription;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MySQL implementation of {@link IDatabaseSubscription}.
 *
 * <p>Currently provides a polling-based heartbeat stub. Real CDC support will be wired
 * via {@code excalibase-watcher-mysql} once it is published to Maven Central.</p>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlDatabaseSubscriptionImplement implements IDatabaseSubscription {
    private static final Logger log = LoggerFactory.getLogger(MysqlDatabaseSubscriptionImplement.class);

    @Override
    public DataFetcher<Publisher<Map<String, Object>>> buildTableSubscriptionResolver(String tableName) {
        return env -> {
            log.info("MySQL subscription resolver called for table: {}", tableName);

            // Heartbeat stream — keeps the WebSocket alive.
            // CDC events will be added when excalibase-watcher-mysql is integrated.
            return Flux.interval(Duration.ofSeconds(30))
                    .map(tick -> {
                        Map<String, Object> event = new HashMap<>();
                        event.put("operation", "HEARTBEAT");
                        event.put("table", tableName);
                        event.put("timestamp", Instant.now().toString());
                        event.put("data", null);
                        return event;
                    });
        };
    }
}
