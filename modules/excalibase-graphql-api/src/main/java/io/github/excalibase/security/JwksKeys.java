package io.github.excalibase.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * The verification keys of an auth service, kept fresh without ever making a request wait on it. When the keys
 * age out or a token names a key id we do not hold, one thread refetches while the rest keep verifying with what
 * they have; a fetch that fails leaves the old keys in place and is not retried for {@code minRetry}, which also
 * bounds how often a stream of unknown key ids can reach the auth service. A rotated key is picked up by the
 * first token that names it, not when the cache runs out.
 */
final class JwksKeys {

    private static final Logger log = LoggerFactory.getLogger(JwksKeys.class);

    record Key(String keyId, ECPublicKey publicKey) {
    }

    @FunctionalInterface
    interface Fetcher {
        List<Key> fetch() throws Exception;
    }

    private record Snapshot(List<Key> keys, long fetchedAtMillis) {
    }

    private final Fetcher fetcher;
    private final long ttlMillis;
    private final long minRetryMillis;
    private final LongSupplier clock;
    private final ReentrantLock refreshing = new ReentrantLock();
    private final AtomicReference<Snapshot> snapshot;
    private volatile long lastAttemptMillis;

    JwksKeys(Fetcher fetcher, Duration ttl, Duration minRetry, LongSupplier millisClock, List<Key> initial) {
        this.fetcher = fetcher;
        this.ttlMillis = ttl.toMillis();
        this.minRetryMillis = minRetry.toMillis();
        this.clock = millisClock;
        this.snapshot = new AtomicReference<>(new Snapshot(List.copyOf(initial), millisClock.getAsLong()));
        this.lastAttemptMillis = millisClock.getAsLong();
    }

    /** The keys a token with {@code keyId} (null when it names none) may be verified with. */
    List<Key> keysFor(String keyId) {
        if (clock.getAsLong() - snapshot.get().fetchedAtMillis() >= ttlMillis) {
            refresh();
        }
        if (keyId != null && snapshot.get().keys().stream().noneMatch(key -> keyId.equals(key.keyId()))) {
            refresh();
        }
        List<Key> keys = snapshot.get().keys();
        if (keyId == null) {
            return keys;
        }
        List<Key> named = keys.stream().filter(key -> keyId.equals(key.keyId())).toList();
        return named.isEmpty() ? keys : named;
    }

    private void refresh() {
        if (clock.getAsLong() - lastAttemptMillis < minRetryMillis || !refreshing.tryLock()) {
            return;
        }
        try {
            if (clock.getAsLong() - lastAttemptMillis < minRetryMillis) {
                return;
            }
            lastAttemptMillis = clock.getAsLong();
            snapshot.set(new Snapshot(List.copyOf(fetcher.fetch()), clock.getAsLong()));
        } catch (Exception e) {
            log.warn("jwt_keys_refresh_failed, keeping the keys held: {}", e.toString());
        } finally {
            refreshing.unlock();
        }
    }
}
