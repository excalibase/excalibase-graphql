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
import java.util.function.Function;

/**
 * A thread-safe cache with TTL (Time To Live) support, similar to Redis expiration.
 * 
 * <p>This cache automatically expires entries after a configured duration and provides
 * Redis-like functionality including:</p>
 * <ul>
 *   <li>Automatic expiration of entries</li>
 *   <li>Background cleanup of expired entries</li>
 *   <li>Thread-safe operations</li>
 *   <li>Compute-if-absent functionality</li>
 * </ul>
 * 
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of cached values
 */
public class TTLCache<K, V> {
    
    private final Map<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ScheduledExecutorService cleanupExecutor;
    
    /**
     * Cache entry wrapper that stores value with expiration time.
     */
    private static class CacheEntry<V> {
        private final V value;
        private final Instant expirationTime;
        
        public CacheEntry(V value, Duration ttl) {
            this.value = value;
            this.expirationTime = Instant.now().plus(ttl);
        }
        
        public V getValue() {
            return value;
        }
        
        public boolean isExpired() {
            return Instant.now().isAfter(expirationTime);
        }
        
        public Instant getExpirationTime() {
            return expirationTime;
        }
    }
    
    /**
     * Creates a new TTL cache with the specified expiration duration.
     * 
     * @param ttl the time-to-live duration for cache entries
     */
    public TTLCache(Duration ttl) {
        this.ttl = ttl;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TTLCache-Cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule cleanup every minute
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Gets a value from the cache, returning null if not found or expired.
     * 
     * @param key the key to look up
     * @return the cached value, or null if not found or expired
     */
    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        
        return entry.getValue();
    }
    
    /**
     * Puts a value in the cache with the configured TTL.
     * 
     * @param key the key to store
     * @param value the value to cache
     * @return the previous value associated with the key, or null if none
     */
    public V put(K key, V value) {
        CacheEntry<V> oldEntry = cache.put(key, new CacheEntry<>(value, ttl));
        return oldEntry != null ? oldEntry.getValue() : null;
    }
    
    /**
     * Computes a value if absent, similar to ConcurrentHashMap.computeIfAbsent().
     * 
     * @param key the key to compute for
     * @param mappingFunction the function to compute the value
     * @return the computed or existing value
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        CacheEntry<V> entry = cache.get(key);
        
        // Check if entry exists and is not expired
        if (entry != null && !entry.isExpired()) {
            return entry.getValue();
        }
        
        // Remove expired entry if it exists
        if (entry != null) {
            cache.remove(key);
        }
        
        // Compute new value
        V newValue = mappingFunction.apply(key);
        if (newValue != null) {
            cache.put(key, new CacheEntry<>(newValue, ttl));
        }
        
        return newValue;
    }
    
    /**
     * Removes a key from the cache.
     * 
     * @param key the key to remove
     * @return the previous value, or null if none
     */
    public V remove(K key) {
        CacheEntry<V> entry = cache.remove(key);
        return entry != null ? entry.getValue() : null;
    }
    
    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * Returns the number of entries in the cache (including potentially expired ones).
     * 
     * @return the size of the cache
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Checks if the cache is empty.
     * 
     * @return true if the cache contains no entries
     */
    public boolean isEmpty() {
        return cache.isEmpty();
    }
    
    /**
     * Gets cache statistics for monitoring.
     * 
     * @return a string representation of cache statistics
     */
    public String getStats() {
        long now = System.currentTimeMillis();
        long expired = cache.values().stream()
                .mapToLong(entry -> entry.isExpired() ? 1 : 0)
                .sum();
        
        return String.format("Cache Stats: total=%d, expired=%d, active=%d, ttl=%s", 
                cache.size(), expired, cache.size() - expired, ttl);
    }
    
    /**
     * Performs cleanup of expired entries.
     */
    private void cleanup() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * Shuts down the cleanup executor. Call this when the cache is no longer needed.
     */
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