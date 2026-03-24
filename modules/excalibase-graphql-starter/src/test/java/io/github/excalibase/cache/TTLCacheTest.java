package io.github.excalibase.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TTLCacheTest {

    private TTLCache<String, String> cache;

    @BeforeEach
    void setUp() {
        cache = new TTLCache<>(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    // ── get / put ───────────────────────────────────────────────────

    @Test
    void put_and_get_returnsValue() {
        cache.put("key", "value");
        assertThat(cache.get("key")).isEqualTo("value");
    }

    @Test
    void put_returnsOldValue() {
        assertThat(cache.put("key", "v1")).isNull();
        assertThat(cache.put("key", "v2")).isEqualTo("v1");
    }

    @Test
    void get_returnsNullForMissingKey() {
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void get_returnsNullForExpiredEntry() throws InterruptedException {
        TTLCache<String, String> shortCache = new TTLCache<>(Duration.ofMillis(50));
        try {
            shortCache.put("key", "value");
            Thread.sleep(100);
            assertThat(shortCache.get("key")).isNull();
        } finally {
            shortCache.shutdown();
        }
    }

    // ── computeIfAbsent ─────────────────────────────────────────────

    @Test
    void computeIfAbsent_computesNewValue() {
        String result = cache.computeIfAbsent("key", k -> "computed");
        assertThat(result).isEqualTo("computed");
        assertThat(cache.get("key")).isEqualTo("computed");
    }

    @Test
    void computeIfAbsent_returnsExistingValue() {
        cache.put("key", "existing");
        String result = cache.computeIfAbsent("key", k -> "new");
        assertThat(result).isEqualTo("existing");
    }

    @Test
    void computeIfAbsent_recomputesExpiredEntry() throws InterruptedException {
        TTLCache<String, String> shortCache = new TTLCache<>(Duration.ofMillis(50));
        try {
            shortCache.put("key", "old");
            Thread.sleep(100);
            String result = shortCache.computeIfAbsent("key", k -> "recomputed");
            assertThat(result).isEqualTo("recomputed");
        } finally {
            shortCache.shutdown();
        }
    }

    @Test
    void computeIfAbsent_doesNotCacheNullResult() {
        String result = cache.computeIfAbsent("key", k -> null);
        assertThat(result).isNull();
        assertThat(cache.size()).isZero();
    }

    // ── remove ──────────────────────────────────────────────────────

    @Test
    void remove_returnsRemovedValue() {
        cache.put("key", "value");
        assertThat(cache.remove("key")).isEqualTo("value");
        assertThat(cache.get("key")).isNull();
    }

    @Test
    void remove_returnsNullForMissingKey() {
        assertThat(cache.remove("missing")).isNull();
    }

    // ── clear / size / isEmpty ──────────────────────────────────────

    @Test
    void clear_removesAllEntries() {
        cache.put("a", "1");
        cache.put("b", "2");
        cache.clear();
        assertThat(cache.size()).isZero();
        assertThat(cache.isEmpty()).isTrue();
    }

    @Test
    void size_reflectsEntryCount() {
        assertThat(cache.size()).isZero();
        cache.put("a", "1");
        assertThat(cache.size()).isEqualTo(1);
        cache.put("b", "2");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void isEmpty_trueInitially() {
        assertThat(cache.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_falseAfterPut() {
        cache.put("key", "value");
        assertThat(cache.isEmpty()).isFalse();
    }

    // ── getStats ────────────────────────────────────────────────────

    @Test
    void getStats_returnsFormattedString() {
        cache.put("a", "1");
        cache.put("b", "2");
        String stats = cache.getStats();
        assertThat(stats)
                .contains("total=2")
                .contains("expired=0")
                .contains("active=2");
    }

    @Test
    void getStats_countsExpiredEntries() throws InterruptedException {
        TTLCache<String, String> shortCache = new TTLCache<>(Duration.ofMillis(50));
        try {
            shortCache.put("a", "1");
            Thread.sleep(100);
            shortCache.put("b", "2"); // this one is still alive
            String stats = shortCache.getStats();
            assertThat(stats).contains("expired=1");
        } finally {
            shortCache.shutdown();
        }
    }

    // ── shutdown ─────────────────────────────────────────────────────

    @Test
    void shutdown_isIdempotent() {
        cache.shutdown();
        // Second shutdown should not throw
        cache.shutdown();
    }
}
