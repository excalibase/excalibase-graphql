package io.github.excalibase.dataloader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExcalibaseBatchLoaderTest {

    private ExcalibaseBatchLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ExcalibaseBatchLoader();
    }

    // ── queueLoad / queueLoads ──────────────────────────────────────

    @Test
    void queueLoad_addsIdToPendingLoads() {
        loader.queueLoad("customer", "id", 1);
        loader.queueLoad("customer", "id", 2);

        Set<Object> ids = loader.getQueuedIds("customer", "id");
        assertThat(ids).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void queueLoad_ignoresNullKeyValue() {
        loader.queueLoad("customer", "id", null);
        assertThat(loader.hasPendingLoads("customer")).isFalse();
    }

    @Test
    void queueLoads_addsMultipleIds() {
        loader.queueLoads("customer", "id", Set.of(1, 2, 3));

        Set<Object> ids = loader.getQueuedIds("customer", "id");
        assertThat(ids).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void queueLoads_ignoresNullOrEmptySet() {
        loader.queueLoads("customer", "id", null);
        loader.queueLoads("customer", "id", Set.of());
        assertThat(loader.hasPendingLoads("customer")).isFalse();
    }

    // ── getQueuedIds ────────────────────────────────────────────────

    @Test
    void getQueuedIds_returnsEmptyForUnknownTable() {
        assertThat(loader.getQueuedIds("unknown", "id")).isEmpty();
    }

    @Test
    void getQueuedIds_returnsEmptyForUnknownColumn() {
        loader.queueLoad("customer", "id", 1);
        assertThat(loader.getQueuedIds("customer", "other")).isEmpty();
    }

    @Test
    void getQueuedIds_excludesCachedIds() {
        loader.queueLoad("customer", "id", 1);
        loader.queueLoad("customer", "id", 2);

        // Cache id=1
        loader.cacheResults("customer", "id", List.of(Map.of("id", 1, "name", "Alice")));

        Set<Object> ids = loader.getQueuedIds("customer", "id");
        assertThat(ids).containsExactly(2);
    }

    @Test
    void getQueuedIds_clearsQueueAfterRetrieval() {
        loader.queueLoad("customer", "id", 1);
        loader.getQueuedIds("customer", "id");

        // Second call should return empty — queue was cleared
        assertThat(loader.getQueuedIds("customer", "id")).isEmpty();
    }

    // ── hasPendingLoads ─────────────────────────────────────────────

    @Test
    void hasPendingLoads_returnsTrueWhenQueued() {
        loader.queueLoad("customer", "id", 1);
        assertThat(loader.hasPendingLoads("customer")).isTrue();
    }

    @Test
    void hasPendingLoads_returnsFalseForUnknownTable() {
        assertThat(loader.hasPendingLoads("unknown")).isFalse();
    }

    // ── cacheResults / getCached / getAllCached ──────────────────────

    @Test
    void cacheResults_storesRecordsByKeyValue() {
        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
        );

        loader.cacheResults("customer", "id", records);

        assertThat(loader.getCached("customer", "id", 1)).containsEntry("name", "Alice");
        assertThat(loader.getCached("customer", "id", 2)).containsEntry("name", "Bob");
    }

    @Test
    void cacheResults_ignoresNullOrEmptyRecords() {
        loader.cacheResults("customer", "id", null);
        loader.cacheResults("customer", "id", List.of());
        assertThat(loader.getAllCached("customer", "id")).isEmpty();
    }

    @Test
    void cacheResults_skipsRecordsWithNullKey() {
        List<Map<String, Object>> records = List.of(
                Map.of("name", "NoId") // missing "id" key
        );
        loader.cacheResults("customer", "id", records);
        assertThat(loader.getAllCached("customer", "id")).isEmpty();
    }

    @Test
    void getCached_returnsNullForNullKeyValue() {
        assertThat(loader.getCached("customer", "id", null)).isNull();
    }

    @Test
    void getCached_returnsNullForUnknownTable() {
        assertThat(loader.getCached("unknown", "id", 1)).isNull();
    }

    @Test
    void getCached_returnsNullForUnknownColumn() {
        loader.cacheResults("customer", "id", List.of(Map.of("id", 1, "name", "Alice")));
        assertThat(loader.getCached("customer", "other", 1)).isNull();
    }

    @Test
    void getAllCached_returnsEmptyForUnknownTable() {
        assertThat(loader.getAllCached("unknown", "id")).isEmpty();
    }

    @Test
    void getAllCached_returnsEmptyForUnknownColumn() {
        loader.cacheResults("customer", "id", List.of(Map.of("id", 1)));
        assertThat(loader.getAllCached("customer", "other")).isEmpty();
    }

    // ── markProcessed / isProcessed ─────────────────────────────────

    @Test
    void markProcessed_returnsTrueFirstTime() {
        assertThat(loader.markProcessed("customer")).isTrue();
    }

    @Test
    void markProcessed_returnsFalseSecondTime() {
        loader.markProcessed("customer");
        assertThat(loader.markProcessed("customer")).isFalse();
    }

    @Test
    void isProcessed_returnsFalseInitially() {
        assertThat(loader.isProcessed("customer")).isFalse();
    }

    @Test
    void isProcessed_returnsTrueAfterMarking() {
        loader.markProcessed("customer");
        assertThat(loader.isProcessed("customer")).isTrue();
    }

    // ── getStats ────────────────────────────────────────────────────

    @Test
    void getStats_returnsFormattedString() {
        loader.cacheResults("customer", "id", List.of(Map.of("id", 1)));
        loader.queueLoad("orders", "id", 10);
        loader.markProcessed("customer");

        String stats = loader.getStats();
        assertThat(stats)
                .contains("cached=1")
                .contains("pending=1")
                .contains("processed_tables=1");
    }

    @Test
    void getStats_returnsZerosWhenEmpty() {
        String stats = loader.getStats();
        assertThat(stats)
                .contains("cached=0")
                .contains("pending=0")
                .contains("processed_tables=0");
    }

    // ── clear ───────────────────────────────────────────────────────

    @Test
    void clear_resetsAllState() {
        loader.queueLoad("customer", "id", 1);
        loader.cacheResults("customer", "id", List.of(Map.of("id", 1)));
        loader.cacheListResults("orders", "customer_id", List.of(Map.of("customer_id", 1)));
        loader.markProcessed("customer");

        loader.clear();

        assertThat(loader.hasPendingLoads("customer")).isFalse();
        assertThat(loader.getCached("customer", "id", 1)).isNull();
        assertThat(loader.getAllCachedList("orders", "customer_id")).isEmpty();
        assertThat(loader.isProcessed("customer")).isFalse();
    }

    // ── cacheListResults / getAllCachedList ──────────────────────────

    @Test
    void cacheListResults_groupsRecordsByKeyColumn() {
        List<Map<String, Object>> records = List.of(
                Map.of("customer_id", 1, "order_id", 10, "total", 100.0),
                Map.of("customer_id", 1, "order_id", 11, "total", 200.0),
                Map.of("customer_id", 2, "order_id", 12, "total", 50.0)
        );

        loader.cacheListResults("orders", "customer_id", records);

        Map<Object, List<Map<String, Object>>> cached = loader.getAllCachedList("orders", "customer_id");
        assertThat(cached).containsKey(1);
        assertThat(cached.get(1)).hasSize(2);
        assertThat(cached).containsKey(2);
        assertThat(cached.get(2)).hasSize(1);
    }

    @Test
    void cacheListResults_ignoresNullOrEmptyRecords() {
        loader.cacheListResults("orders", "customer_id", null);
        loader.cacheListResults("orders", "customer_id", List.of());
        assertThat(loader.getAllCachedList("orders", "customer_id")).isEmpty();
    }

    @Test
    void getAllCachedList_returnsEmptyMapWhenNothingCached() {
        Map<Object, List<Map<String, Object>>> result = loader.getAllCachedList("orders", "customer_id");
        assertThat(result).isEmpty();
    }

    @Test
    void getAllCachedList_returnsEmptyMapForUnknownColumn() {
        List<Map<String, Object>> records = List.of(
                Map.of("customer_id", 1, "order_id", 10)
        );
        loader.cacheListResults("orders", "customer_id", records);

        Map<Object, List<Map<String, Object>>> result = loader.getAllCachedList("orders", "other_col");
        assertThat(result).isEmpty();
    }

    @Test
    void cacheListResults_appendsToExistingCache() {
        loader.cacheListResults("orders", "customer_id", List.of(
                Map.of("customer_id", 1, "order_id", 10)
        ));
        loader.cacheListResults("orders", "customer_id", List.of(
                Map.of("customer_id", 1, "order_id", 11),
                Map.of("customer_id", 2, "order_id", 12)
        ));

        Map<Object, List<Map<String, Object>>> cached = loader.getAllCachedList("orders", "customer_id");
        assertThat(cached.get(1)).hasSize(2);
        assertThat(cached.get(2)).hasSize(1);
    }
}
