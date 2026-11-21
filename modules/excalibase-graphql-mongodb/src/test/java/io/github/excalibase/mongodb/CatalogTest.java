package io.github.excalibase.mongodb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTest {

    private static Map<String, Object> collection(String name, Map<String, Object> fields) {
        return Map.of("name", name, "id", "objectId", "fields", fields);
    }

    @Test
    void readsDeclaredCollections() {
        Catalog catalog = Catalog.fromSettings(Map.of("collections", List.of(
                collection("orders", Map.of("status", "string", "total", "int")))));

        CollectionDef orders = catalog.collection("orders").orElseThrow();
        assertThat(orders.idKind()).isEqualTo(FieldKind.OBJECT_ID);
        assertThat(orders.fields()).containsEntry("status", FieldKind.STRING).containsEntry("total", FieldKind.INT);
    }

    @Test
    void refusesWhenNothingIsDeclared() {
        Map<String, Object> settings1 = Map.of();
        assertThatThrownBy(() -> Catalog.fromSettings(settings1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collections");
    }

    @Test
    void refusesNamesThatAreNotGraphqlNames() {
        Map<String, Object> settings2 = Map.of("collections", List.of(
                collection("bad-name", Map.of("a", "string"))));
        assertThatThrownBy(() -> Catalog.fromSettings(settings2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-name");
        Map<String, Object> settings3 = Map.of("collections", List.of(
                collection("orders", Map.of("$where", "string"))));
        assertThatThrownBy(() -> Catalog.fromSettings(settings3))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("$where");
    }

    @Test
    void refusesFieldsThatCollideWithTheId() {
        Map<String, Object> settings4 = Map.of("collections", List.of(
                collection("orders", Map.of("id", "string"))));
        assertThatThrownBy(() -> Catalog.fromSettings(settings4))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
    }

    @Test
    void refusesAnUnknownType() {
        Map<String, Object> settings5 = Map.of("collections", List.of(
                collection("orders", Map.of("a", "geometry"))));
        assertThatThrownBy(() -> Catalog.fromSettings(settings5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("geometry");
    }
}
