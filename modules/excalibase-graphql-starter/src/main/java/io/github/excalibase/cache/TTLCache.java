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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Thread-safe cache with TTL (Time To Live) support.
 * Supports an optional onEvict callback for resource cleanup (e.g., closing connection pools).
 * computeIfAbsent is atomic per key — only one thread computes for a given key.
 */
public class TTLCache<K, V> {

    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Consumer<V> onEvict;
    private final ScheduledExecutorService cleanupExecutor;

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
     * Atomic compute-if-absent with expiry check.
     * Uses ConcurrentHashMap.compute to ensure only one thread creates the value per key.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        var result = new AtomicReference<CacheEntry<V>>();
        cache.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired()) {
                result.set(existing);
                return existing;
            }
            if (existing != null && onEvict != null) {
                onEvict.accept(existing.getValue());
            }
            V newValue = mappingFunction.apply(k);
            if (newValue == null) return null;
            CacheEntry<V> newEntry = new CacheEntry<>(newValue, ttl);
            result.set(newEntry);
            return newEntry;
        });
        return result.get() != null ? result.get().getValue() : null;
    }

    public V remove(K key) {
        CacheEntry<V> entry = cache.remove(key);
        if (entry != null) {
            if (onEvict != null) onEvict.accept(entry.getValue());
            return entry.getValue();
        }
        return null;
    }

    public void clear() {
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
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
