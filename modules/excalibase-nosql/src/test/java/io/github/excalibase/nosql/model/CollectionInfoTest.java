package io.github.excalibase.nosql.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionInfoTest {

    private CollectionInfo info;

    @BeforeEach
    void setUp() {
        info = new CollectionInfo();
    }

    @Nested
    @DisplayName("Collection registry")
    class Registry {

        @Test
        @DisplayName("add and get collection")
        void addAndGet() {
            var schema = new CollectionSchema(
                    "users",
                    Map.of("email", FieldType.STRING, "age", FieldType.NUMBER),
                    List.of(new IndexDef(List.of("email"), "string", true)),
                    Set.of("email"),
                    null, null);
            info.addCollection("users", schema);

            assertThat(info.getCollection("users")).isPresent();
            assertThat(info.getCollection("users").get().name()).isEqualTo("users");
            assertThat(info.hasCollection("users")).isTrue();
            assertThat(info.getCollectionNames()).containsExactly("users");
        }

        @Test
        @DisplayName("get unknown collection returns empty")
        void getUnknown() {
            assertThat(info.getCollection("unknown")).isEmpty();
            assertThat(info.hasCollection("unknown")).isFalse();
        }

        @Test
        @DisplayName("remove collection")
        void remove() {
            var schema = new CollectionSchema("users", Map.of(), List.of(), Set.of(), null, null);
            info.addCollection("users", schema);
            info.removeCollection("users");

            assertThat(info.hasCollection("users")).isFalse();
            assertThat(info.getCollectionNames()).isEmpty();
        }

        @Test
        @DisplayName("clearAll removes everything")
        void clearAll() {
            info.addCollection("a", new CollectionSchema("a", Map.of(), List.of(), Set.of(), null, null));
            info.addCollection("b", new CollectionSchema("b", Map.of(), List.of(), Set.of(), null, null));
            info.clearAll();

            assertThat(info.getCollectionNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Query validation")
    class QueryValidation {

        private CollectionSchema schema;

        @BeforeEach
        void setUp() {
            schema = new CollectionSchema(
                    "users",
                    Map.of("email", FieldType.STRING, "name", FieldType.STRING, "age", FieldType.NUMBER),
                    List.of(
                            new IndexDef(List.of("email"), "string", true),
                            new IndexDef(List.of("age"), "number", false)
                    ),
                    Set.of("email", "age"),
                    null, null);
        }

        @Test
        @DisplayName("indexed field returns no warnings")
        void indexedFieldNoWarnings() {
            assertThat(schema.checkIndexes(Set.of("email"))).isEmpty();
            assertThat(schema.checkIndexes(Set.of("age"))).isEmpty();
            assertThat(schema.checkIndexes(Set.of("email", "age"))).isEmpty();
        }

        @Test
        @DisplayName("unindexed field returns warning")
        void unindexedFieldWarns() {
            var warnings = schema.checkIndexes(Set.of("name"));
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst()).contains("name").contains("not indexed");
        }

        @Test
        @DisplayName("id field never warns")
        void idFieldNeverWarns() {
            assertThat(schema.checkIndexes(Set.of("id"))).isEmpty();
        }

        @Test
        @DisplayName("mixed indexed and unindexed warns for unindexed only")
        void mixedWarns() {
            var warnings = schema.checkIndexes(Set.of("email", "name"));
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst()).contains("name");
        }

        @Test
        @DisplayName("empty filter returns no warnings")
        void emptyFilterNoWarnings() {
            assertThat(schema.checkIndexes(Set.of())).isEmpty();
        }
    }
}
