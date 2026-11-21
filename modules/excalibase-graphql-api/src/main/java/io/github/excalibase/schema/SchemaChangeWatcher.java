package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Notices DDL on the one database a standalone engine serves. In the platform a DDL event from the watcher
 * evicts the schema; a standalone engine has no watcher, so without this a table created after start would
 * stay invisible until the cache expired or the engine restarted (EXC-802).
 *
 * <p>It runs only where no other signal exists: not with NATS (the events do it) and not with tenant routing
 * (each project's reflection is evicted on its own signals). Every {@code app.cache.schema-poll-seconds} it
 * runs the loader's catalog fingerprint, one query, and reloads on a difference. {@code 0} turns it off.
 */
@Context
public class SchemaChangeWatcher {

    private static final Logger log = LoggerFactory.getLogger(SchemaChangeWatcher.class);

    private final GraphqlSchemaManager schemas;
    private final long pollSeconds;
    private final boolean otherSignalExists;
    private ScheduledExecutorService scheduler;
    private volatile boolean failing;

    @Inject
    public SchemaChangeWatcher(GraphqlSchemaManager schemas,
                               @Value("${app.cache.schema-poll-seconds:5}") long pollSeconds,
                               @Value("${app.nats.enabled:false}") boolean natsEnabled,
                               @Nullable DynamicDataSourceManager tenants) {
        this.schemas = schemas;
        this.pollSeconds = pollSeconds;
        this.otherSignalExists = natsEnabled || tenants != null;
    }

    @PostConstruct
    void start() {
        if (pollSeconds <= 0) {
            log.info("schema_poll_disabled: app.cache.schema-poll-seconds is 0");
            return;
        }
        if (otherSignalExists) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "schema-change-watcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::poll, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        log.info("schema_poll_started interval={}s", pollSeconds);
    }

    /** One check. Never throws: a database that is down is logged once per outage and asked again next time. */
    void poll() {
        try {
            schemas.reloadIfSchemaChanged();
            failing = false;
        } catch (RuntimeException e) {
            if (!failing) {
                log.warn("schema_poll_failed: {}; the schema served is unchanged", e.getMessage());
            }
            failing = true;
        }
    }

    boolean running() {
        return scheduler != null && !scheduler.isShutdown();
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
