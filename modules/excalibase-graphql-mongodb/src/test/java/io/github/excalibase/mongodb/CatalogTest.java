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
        assertThatThrownBy(() -> Catalog.fromSettings(Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("collections");
    }

    @Test
    void refusesNamesThatAreNotGraphqlNames() {
        assertThatThrownBy(() -> Catalog.fromSettings(Map.of("collections", List.of(
                collection("bad-name", Map.of("a", "string"))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-name");
        assertThatThrownBy(() -> Catalog.fromSettings(Map.of("collections", List.of(
                collection("orders", Map.of("$where", "string"))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("$where");
    }

    @Test
    void refusesFieldsThatCollideWithTheId() {
        assertThatThrownBy(() -> Catalog.fromSettings(Map.of("collections", List.of(
                collection("orders", Map.of("id", "string"))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
    }

    @Test
    void refusesAnUnknownType() {
        assertThatThrownBy(() -> Catalog.fromSettings(Map.of("collections", List.of(
                collection("orders", Map.of("a", "geometry"))))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("geometry");
    }
}
