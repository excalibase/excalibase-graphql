package io.github.excalibase.dataloader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExcalibaseBatchLoaderTest {

    private ExcalibaseBatchLoader loader;

    @BeforeEach
    void setUp() {
        loader = new ExcalibaseBatchLoader();
    }

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
    void clear_alsoClearsListCache() {
        loader.cacheListResults("orders", "customer_id", List.of(
                Map.of("customer_id", 1, "order_id", 10)
        ));
        loader.clear();

        assertThat(loader.getAllCachedList("orders", "customer_id")).isEmpty();
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
