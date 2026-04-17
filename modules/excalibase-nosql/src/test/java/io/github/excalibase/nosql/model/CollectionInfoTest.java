package io.github.excalibase.nosql.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        @DisplayName("indexed field passes validation")
        void indexedFieldPasses() {
            schema.validateQuery(Set.of("email"), false);
            schema.validateQuery(Set.of("age"), false);
            schema.validateQuery(Set.of("email", "age"), false);
        }

        @Test
        @DisplayName("unindexed field throws without allowScan")
        void unindexedFieldThrows() {
            assertThatThrownBy(() -> schema.validateQuery(Set.of("name"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name")
                    .hasMessageContaining("not indexed");
        }

        @Test
        @DisplayName("unindexed field passes with allowScan")
        void unindexedFieldPassesWithAllowScan() {
            schema.validateQuery(Set.of("name"), true);
        }

        @Test
        @DisplayName("id field always passes without index")
        void idFieldAlwaysPasses() {
            schema.validateQuery(Set.of("id"), false);
        }

        @Test
        @DisplayName("mixed indexed and unindexed throws")
        void mixedThrows() {
            assertThatThrownBy(() -> schema.validateQuery(Set.of("email", "name"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        @DisplayName("empty filter passes")
        void emptyFilterPasses() {
            schema.validateQuery(Set.of(), false);
        }
    }
}
