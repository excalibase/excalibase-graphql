package io.github.excalibase.schema;

import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;
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

    @Test
    void aColumnsDeclaration_followsItUnderTheQualifiedTable() {
        SchemaInfo loaded = new SchemaInfo();
        loaded.addColumn("notes", "pinned", "tinyint");
        loaded.addColumnDeclaration("notes", "pinned", new ColumnDeclaration("tinyint(1)", null, null));
        loaded.addColumn("notes", "id", "int");
        SchemaInfo merged = new SchemaInfo();

        new SchemaMerger().merge(merged, "shop", loaded);

        assertThat(merged.getColumnDeclaration("shop.notes", "pinned")).isEqualTo(new ColumnDeclaration("tinyint(1)", null, null));
        assertThat(merged.getColumnDeclaration("shop.notes", "id")).isNull();
    }
}
