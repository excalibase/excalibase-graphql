package io.github.excalibase.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Projects the vault recently answered 404 for, so requests naming a missing
 * project do not each cost a vault call. Size-bounded (least recently used goes
 * first) so a stream of random ids cannot grow memory, and short-lived so a
 * project created after the 404 is reachable once the entry expires. Only a 404
 * is recorded: a vault error says nothing about whether the project exists.
 */
public final class UnknownProjectCache {

    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Long> recordedAt;

    public UnknownProjectCache(int maxEntries, Duration ttl, LongSupplier clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
        this.recordedAt = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > UnknownProjectCache.this.maxEntries;
            }
        };
    }

    public synchronized boolean isKnownMissing(String projectId) {
        Long at = recordedAt.get(projectId);
        if (at == null) {
            return false;
        }
        if (clock.getAsLong() - at >= ttlMillis) {
            recordedAt.remove(projectId);
            return false;
        }
        return true;
    }

    public synchronized void recordMissing(String projectId) {
        recordedAt.put(projectId, clock.getAsLong());
    }

    public synchronized int size() {
        return recordedAt.size();
    }
}
