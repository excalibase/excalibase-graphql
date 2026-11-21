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
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final long MAX_BACKOFF_SECONDS = 300;
    private static final Logger log = LoggerFactory.getLogger(SchemaChangeWatcher.class);

    private final GraphqlSchemaManager schemas;
    private final long pollSeconds;
    private final boolean otherSignalExists;
    private ScheduledExecutorService scheduler;
    private final AtomicInteger failures = new AtomicInteger();

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
        scheduler.schedule(this::tick, pollSeconds, TimeUnit.SECONDS);
        log.info("schema_poll_started interval={}s", pollSeconds);
    }

    private void tick() {
        poll();
        if (running()) {
            scheduler.schedule(this::tick, nextDelaySeconds(), TimeUnit.SECONDS);
        }
    }

    long nextDelaySeconds() {
        return delayAfter(pollSeconds, failures.get());
    }

    /** The wait before the next check: the interval, doubled for each failure in a row, up to a ceiling. */
    static long delayAfter(long intervalSeconds, int failures) {
        long ceiling = Math.max(intervalSeconds, MAX_BACKOFF_SECONDS);
        long delay = intervalSeconds;
        for (int failure = 0; failure < failures && delay < ceiling; failure++) {
            delay *= 2;
        }
        return Math.min(delay, ceiling);
    }

    /** One check. Never throws: a database that is down is logged once per outage and asked again later. */
    void poll() {
        try {
            schemas.reloadIfSchemaChanged();
            int failed = failures.getAndSet(0);
            if (failed > 0) {
                log.info("schema_poll_recovered after {} failed checks", failed);
            }
        } catch (RuntimeException e) {
            if (failures.getAndIncrement() == 0) {
                log.warn("schema_poll_failed: {}; the schema served is unchanged and the check backs off", e.getMessage());
            }
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
