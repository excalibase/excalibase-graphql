package io.github.excalibase.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMergerTest {

    @Test
    void aMergedColumnKeepsItsFullTextIndex() {
        SchemaInfo source = new SchemaInfo();
        source.addColumn("article", "body", "text");
        source.addColumn("article", "title", "varchar");
        source.addFullTextColumn("article", "body");
        SchemaInfo target = new SchemaInfo();

        new SchemaMerger().merge(target, "app", source);

        assertThat(target.isFullTextColumn("app.article", "body")).isTrue();
        assertThat(target.isFullTextColumn("app.article", "title")).isFalse();
    }
}
