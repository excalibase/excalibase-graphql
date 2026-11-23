package io.github.excalibase.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Composite keys of one table to one parent are each named after their columns; one key keeps the plain name. */
class CompositeForeignKeyNamingTest {

    private static void twoKeys(SchemaInfo schema) {
        schema.addCompositeForeignKey("public.child", List.of("t", "a"), "public.parent", List.of("t", "id"));
        schema.addCompositeForeignKey("public.child", List.of("t", "b"), "public.parent", List.of("t", "id"));
    }

    @Test
    void oneKey_keepsThePlainName() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey("public.child", List.of("t", "a"), "public.parent", List.of("t", "id"));

        assertThat(schema.getAllForwardFks()).containsOnlyKeys("public.child.publicParent");
    }

    @Test
    void theSameKeyAddedTwice_isStillOneKeyWithThePlainName() {
        SchemaInfo schema = new SchemaInfo();
        for (int i = 0; i < 2; i++) {
            schema.addCompositeForeignKey("public.child", List.of("t", "a"), "public.parent", List.of("t", "id"));
        }

        assertThat(schema.getAllForwardFks()).containsOnlyKeys("public.child.publicParent");
    }

    @Test
    void twoKeys_areEachNamedAfterTheirColumns_inAnyOrder() {
        SchemaInfo schema = new SchemaInfo();
        twoKeys(schema);

        assertThat(schema.getAllForwardFks()).hasSize(2).doesNotContainKey("public.child.publicParent");
    }

    @Test
    void afterClearAll_aSingleKeyIsPlainAgain_notNamedAsIfItHadAFellow() {
        SchemaInfo schema = new SchemaInfo();
        twoKeys(schema);
        schema.clearAll();
        schema.addCompositeForeignKey("public.child", List.of("t", "a"), "public.parent", List.of("t", "id"));

        assertThat(schema.getAllForwardFks()).containsOnlyKeys("public.child.publicParent");
    }
}
