package io.github.excalibase.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Composite keys of one table to one parent are each named after their columns; one key keeps the plain name. */
class CompositeForeignKeyNamingTest {

    private static final String CHILD = "public.child";
    private static final String PARENT = "public.parent";
    private static final String FIELD_NAME = "[_A-Za-z][_0-9A-Za-z]*";

    private static void twoKeys(SchemaInfo schema) {
        schema.addCompositeForeignKey(CHILD, List.of("t", "a"), PARENT, List.of("t", "id"));
        schema.addCompositeForeignKey(CHILD, List.of("t", "b"), PARENT, List.of("t", "id"));
    }

    @Test
    void oneKey_keepsThePlainName() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey(CHILD, List.of("t", "a"), PARENT, List.of("t", "id"));

        assertThat(schema.getAllForwardFks()).containsOnlyKeys("public.child.publicParent");
    }

    @Test
    void theSameKeyAddedTwice_isStillOneKeyWithThePlainName() {
        SchemaInfo schema = new SchemaInfo();
        for (int i = 0; i < 2; i++) {
            schema.addCompositeForeignKey(CHILD, List.of("t", "a"), PARENT, List.of("t", "id"));
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
        schema.addCompositeForeignKey(CHILD, List.of("t", "a"), PARENT, List.of("t", "id"));

        assertThat(schema.getAllForwardFks()).containsOnlyKeys("public.child.publicParent");
    }

    @Test
    void twoKeysOverTheSameColumns_areBothKept_toldApartByTheirConstraintNames() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "by_pk");
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("c", "d"), "by_alt");

        assertThat(schema.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::constraintName)
                .containsExactlyInAnyOrder("by_pk", "by_alt");
        assertThat(schema.getAllReverseFks().values()).extracting(SchemaInfo.ReverseFkInfo::constraintName)
                .containsExactlyInAnyOrder("by_pk", "by_alt");
    }

    @Test
    void theSameKeyDeclaredTwice_isOneRelationship_withThePlainName() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "one");
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "two");
        schema.addForeignKey("public.line", "p", null, PARENT, "a", "line_one");
        schema.addForeignKey("public.line", "p", null, PARENT, "a", "line_two");

        assertThat(schema.getAllForwardFks()).hasSize(2).containsKey("public.child.publicParent");
        assertThat(schema.getAllReverseFks()).hasSize(2).containsKey("public.parent.publicChild")
                .containsKey("public.parent.publicLine");
    }

    @Test
    void aConstraintNameThatIsNotAFieldName_becomesOne() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "fk-1.by pk");
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("c", "d"), "fk-2.by alt");

        assertThat(schema.getAllForwardFks().keySet()).hasSize(2)
                .allSatisfy(key -> assertThat(key.substring(key.lastIndexOf('.') + 1)).matches(FIELD_NAME));
        assertThat(schema.getAllReverseFks().keySet()).hasSize(2)
                .allSatisfy(key -> assertThat(key.substring(key.lastIndexOf('.') + 1)).matches(FIELD_NAME));
    }

    @Test
    void aSingleColumnKeyDeclaredTwice_keepsTheFirstName_forwardAndReverseAlike() {
        SchemaInfo schema = new SchemaInfo();
        schema.addForeignKey("public.line", "p", null, PARENT, "a", "line_one");
        schema.addForeignKey("public.line", "p", null, PARENT, "a", "line_two");

        assertThat(schema.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::constraintName)
                .containsExactly("line_one");
        assertThat(schema.getAllReverseFks().values()).extracting(SchemaInfo.ReverseFkInfo::constraintName)
                .containsExactly("line_one");
    }

    @Test
    void constraintNamesThatSanitiseToTheSameName_areBothKept_underDifferentNames() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "fk-a");
        schema.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("c", "d"), "fk.a");

        assertThat(schema.getAllForwardFks()).hasSize(2);
        assertThat(schema.getAllForwardFks().values()).extracting(SchemaInfo.FkInfo::constraintName)
                .containsExactlyInAnyOrder("fk-a", "fk.a");
        assertThat(schema.getAllReverseFks()).hasSize(2);
        assertThat(schema.getAllReverseFks().values()).extracting(SchemaInfo.ReverseFkInfo::constraintName)
                .containsExactlyInAnyOrder("fk-a", "fk.a");
    }

    @Test
    void theNamesOfCollidingKeys_doNotDependOnTheOrderTheyAreAdded() {
        SchemaInfo forward = new SchemaInfo();
        forward.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "fk-a");
        forward.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("c", "d"), "fk.a");
        SchemaInfo backward = new SchemaInfo();
        backward.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("c", "d"), "fk.a");
        backward.addCompositeForeignKey(CHILD, List.of("x", "y"), null, PARENT, List.of("a", "b"), "fk-a");

        assertThat(backward.getAllForwardFks().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> e.getValue().constraintName())))
                .isEqualTo(forward.getAllForwardFks().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> e.getValue().constraintName())));
    }
}
