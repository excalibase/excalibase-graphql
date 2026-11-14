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
package io.github.excalibase.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Thread-safe cache with TTL (Time To Live) support.
 * Supports an optional onEvict callback for resource cleanup (e.g., closing connection pools).
 * computeIfAbsent builds a missing value on one thread per key, outside any lock.
 */
public class TTLCache<K, V> {

    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final Map<K, Build<V>> inFlight = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Consumer<V> onEvict;
    private final ScheduledExecutorService cleanupExecutor;

    /** A value being built for one key; an evict of that key while it runs marks it stale. */
    private static final class Build<V> extends CompletableFuture<V> {
        private volatile boolean stale;
    }

    private static final int REBUILDS = 3;

    private static class CacheEntry<V> {
        private final V value;
        private final Instant expirationTime;

        CacheEntry(V value, Duration ttl) {
            this.value = value;
            this.expirationTime = Instant.now().plus(ttl);
        }

        V getValue() { return value; }

        boolean isExpired() { return Instant.now().isAfter(expirationTime); }
    }

    public TTLCache(Duration ttl) {
        this(ttl, null);
    }

    public TTLCache(Duration ttl, Consumer<V> onEvict) {
        this.ttl = ttl;
        this.onEvict = onEvict;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TTLCache-Cleanup");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            evictEntry(key, entry);
            return null;
        }
        return entry.getValue();
    }

    public V put(K key, V value) {
        CacheEntry<V> oldEntry = cache.put(key, new CacheEntry<>(value, ttl));
        if (oldEntry != null && onEvict != null) {
            onEvict.accept(oldEntry.getValue());
        }
        return oldEntry != null ? oldEntry.getValue() : null;
    }

    /**
     * The cached value, or the one {@code mappingFunction} builds. Only one thread builds a value per key and the
     * others wait for it; the build runs outside any map lock, so a slow or hanging one blocks neither the cached
     * keys nor the other keys' builds. A failure reaches every waiter and is not cached. A value whose key was
     * evicted while it was being built is released through onEvict and built again, never kept.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        int rebuilds = 0;
        while (true) {
            V cached = get(key);
            if (cached != null) return cached;
            Build<V> mine = new Build<>();
            Build<V> running = inFlight.putIfAbsent(key, mine);
            if (running != null) {
                V shared = await(running);
                if (!running.stale) return shared;
                continue;
            }
            try {
                V value = get(key);
                if (value == null) {
                    value = mappingFunction.apply(key);
                    if (value != null && !keep(key, value, mine)) {
                        mine.complete(value);
                        // Evicted again and again while being built: the last value is handed out, never cached.
                        if (rebuilds++ < REBUILDS) continue;
                        return value;
                    }
                }
                mine.complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                inFlight.remove(key, mine);
            }
        }
    }

    /**
     * Stores a freshly built value unless its key was evicted meanwhile, in which case it is released and false
     * comes back. The check runs after the put as well: an evict marks the build before it touches the map, so
     * one of the two sides always sees the other.
     */
    private boolean keep(K key, V value, Build<V> build) {
        if (build.stale) {
            release(value);
            return false;
        }
        CacheEntry<V> entry = new CacheEntry<>(value, ttl);
        CacheEntry<V> previous = cache.put(key, entry);
        if (previous != null && onEvict != null) onEvict.accept(previous.getValue());
        if (build.stale) {
            evictEntry(key, entry);
            return false;
        }
        return true;
    }

    private void release(V value) {
        if (onEvict != null) onEvict.accept(value);
    }

    private void markStale(Predicate<? super K> keyFilter) {
        inFlight.forEach((key, build) -> {
            if (keyFilter.test(key)) build.stale = true;
        });
    }

    private static <V> V await(CompletableFuture<V> running) {
        try {
            return running.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a cache value", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException(e.getCause());
        }
    }

    public V remove(K key) {
        markStale(key::equals);
        CacheEntry<V> entry = cache.remove(key);
        if (entry != null) {
            if (onEvict != null) onEvict.accept(entry.getValue());
            return entry.getValue();
        }
        return null;
    }

    /** Removes every entry whose key matches, running onEvict for each. */
    public void removeIf(Predicate<? super K> keyFilter) {
        markStale(keyFilter);
        for (K key : Set.copyOf(cache.keySet())) {
            if (keyFilter.test(key)) {
                remove(key);
            }
        }
    }

    public void clear() {
        markStale(key -> true);
        if (onEvict != null) {
            cache.values().forEach(entry -> onEvict.accept(entry.getValue()));
        }
        cache.clear();
    }

    public int size() { return cache.size(); }

    public boolean isEmpty() { return cache.isEmpty(); }

    private void evictEntry(K key, CacheEntry<V> entry) {
        if (cache.remove(key, entry) && onEvict != null) {
            onEvict.accept(entry.getValue());
        }
    }

    private void cleanup() {
        cache.forEach((key, entry) -> {
            if (entry.isExpired()) {
                evictEntry(key, entry);
            }
        });
    }

    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException _) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
