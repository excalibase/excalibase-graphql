package io.github.excalibase.cache

import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.util.concurrent.TimeUnit

class TTLCacheTest extends Specification {

    def "should create empty cache"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        expect:
        cache.isEmpty()
        cache.size() == 0
        cache.get("nonexistent") == null
    }

    def "should store and retrieve values"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        when:
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        then:
        !cache.isEmpty()
        cache.size() == 2
        cache.get("key1") == "value1"
        cache.get("key2") == "value2"
        cache.get("nonexistent") == null
    }

    def "should expire entries after TTL"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMillis(100))

        when:
        cache.put("key1", "value1")

        then:
        cache.get("key1") == "value1"

        when:
        Thread.sleep(150) // Wait for expiration

        then:
        cache.get("key1") == null
        cache.size() == 0 // Entry should be removed when accessed after expiration
    }

    def "should support computeIfAbsent"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))
        def computeCount = 0

        when:
        def result1 = cache.computeIfAbsent("key1") {
            computeCount++
            return "computed-value-1"
        }

        then:
        result1 == "computed-value-1"
        computeCount == 1
        cache.get("key1") == "computed-value-1"

        when:
        def result2 = cache.computeIfAbsent("key1") {
            computeCount++
            return "computed-value-2"
        }

        then:
        result2 == "computed-value-1" // Should return cached value
        computeCount == 1 // Should not compute again
    }

    def "should recompute if entry expired in computeIfAbsent"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMillis(100))
        def computeCount = 0

        when:
        def result1 = cache.computeIfAbsent("key1") {
            computeCount++
            return "computed-value-1"
        }

        then:
        result1 == "computed-value-1"
        computeCount == 1

        when:
        Thread.sleep(150) // Wait for expiration
        def result2 = cache.computeIfAbsent("key1") {
            computeCount++
            return "computed-value-2"
        }

        then:
        result2 == "computed-value-2"
        computeCount == 2
    }

    def "should clear all entries"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        when:
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        then:
        cache.size() == 2

        when:
        cache.clear()

        then:
        cache.isEmpty()
        cache.size() == 0
        cache.get("key1") == null
        cache.get("key2") == null
    }

    def "should remove specific entries"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        when:
        cache.put("key1", "value1")
        cache.put("key2", "value2")

        then:
        cache.size() == 2

        when:
        def removed = cache.remove("key1")

        then:
        removed == "value1"
        cache.size() == 1
        cache.get("key1") == null
        cache.get("key2") == "value2"
    }

    def "should return cache stats"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        when:
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        def stats = cache.getStats()

        then:
        stats != null
        stats.contains("total=2")
        stats.contains("active=2")
        stats.contains("expired=0")
        stats.contains("ttl=PT5M")
    }

    def "should handle concurrent access"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))
        def threads = []
        def results = Collections.synchronizedList([])

        when:
        10.times { i ->
            threads << Thread.start {
                def value = cache.computeIfAbsent("shared-key") {
                    Thread.sleep(10) // Simulate some work
                    return "computed-value"
                }
                results.add(value)
            }
        }

        threads.each { it.join() }

        then:
        results.size() == 10
        results.every { it == "computed-value" }
        cache.get("shared-key") == "computed-value"
    }

    def "should handle null values"() {
        given:
        def cache = new TTLCache<String, String>(Duration.ofMinutes(5))

        when:
        def result = cache.computeIfAbsent("key1") { null }

        then:
        result == null
        cache.get("key1") == null
        cache.size() == 0
    }

    def cleanup() {
        // Ensure all caches are properly shut down
        // This is typically handled by @PreDestroy in real usage
    }
} 