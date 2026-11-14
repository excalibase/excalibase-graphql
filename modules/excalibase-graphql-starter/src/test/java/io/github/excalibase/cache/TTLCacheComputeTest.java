package io.github.excalibase.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXC-722/723: a value is built outside any map lock, once per key, and a failure reaches every waiter. */
class TTLCacheComputeTest {

    /** Every key lands in the same map bin, as unlucky keys do. */
    private record SameBin(String name) {
        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    void aHangingBuildDoesNotBlockAnotherKeyInTheSameBin() throws Exception {
        TTLCache<SameBin, String> cache = new TTLCache<>(Duration.ofMinutes(1));
        cache.put(new SameBin("cached"), "ready");
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = pool.submit(() -> cache.computeIfAbsent(new SameBin("slow"), key -> {
                building.countDown();
                await(release);
                return "built";
            }));
            assertThat(building.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> read = pool.submit(() -> cache.get(new SameBin("cached")));
            Future<String> other = pool.submit(() -> cache.computeIfAbsent(new SameBin("other"), key -> "quick"));

            assertThat(read.get(2, TimeUnit.SECONDS)).isEqualTo("ready");
            assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo("quick");
            release.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("built");
        } finally {
            release.countDown();
            pool.shutdownNow();
            cache.shutdown();
        }
    }

    @Test
    void concurrentCallersForOneKeyShareOneBuild() throws Exception {
        TTLCache<String, String> cache = new TTLCache<>(Duration.ofMinutes(1));
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> callers = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> cache.computeIfAbsent("key", key -> {
                        builds.incrementAndGet();
                        await(release);
                        return "value";
                    }))).toList();
            Thread.sleep(200);
            release.countDown();
            for (Future<String> caller : callers) {
                assertThat(caller.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            }
            assertThat(builds).hasValue(1);
        } finally {
            pool.shutdownNow();
            cache.shutdown();
        }
    }

    @Test
    void aFailedBuildIsRethrownAsItIsAndNotCached() {
        TTLCache<String, String> cache = new TTLCache<>(Duration.ofMinutes(1));
        IllegalStateException failure = new IllegalStateException("down");
        try {
            assertThatThrownBy(() -> cache.computeIfAbsent("key", key -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(cache.computeIfAbsent("key", key -> "recovered")).isEqualTo("recovered");
        } finally {
            cache.shutdown();
        }
    }

    /** Runs a build that is still going when {@code invalidate} fires, then lets it finish. */
    private static void evictDuringBuild(java.util.function.Consumer<TTLCache<String, String>> invalidate,
                                         List<String> evicted) throws Exception {
        TTLCache<String, String> cache = new TTLCache<>(Duration.ofMinutes(1), evicted::add);
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger builds = new AtomicInteger();
            Future<String> build = pool.submit(() -> cache.computeIfAbsent("schema", key -> {
                if (builds.incrementAndGet() > 1) {
                    return "fresh";
                }
                building.countDown();
                await(release);
                return "stale";
            }));
            assertThat(building.await(5, TimeUnit.SECONDS)).isTrue();
            invalidate.accept(cache);
            release.countDown();
            assertThat(build.get(5, TimeUnit.SECONDS)).as("the caller gets a value built after the evict").isEqualTo("fresh");
            assertThat(cache.get("schema")).as("the build that began before the evict is not kept").isEqualTo("fresh");
        } finally {
            pool.shutdownNow();
            cache.shutdown();
        }
    }

    @Test
    void aRemoveDuringABuildIsNotLost() throws Exception {
        evictDuringBuild(cache -> cache.remove("schema"), new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    @Test
    void aRemoveIfDuringABuildIsNotLost() throws Exception {
        evictDuringBuild(cache -> cache.removeIf("schema"::equals), new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    @Test
    void aClearDuringABuildIsNotLost() throws Exception {
        evictDuringBuild(TTLCache::clear, new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    @Test
    void aValueBuiltBeforeAnEvictIsReleasedThroughOnEvict() throws Exception {
        List<String> evicted = new java.util.concurrent.CopyOnWriteArrayList<>();
        evictDuringBuild(cache -> cache.remove("schema"), evicted);
        assertThat(evicted).contains("stale");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
