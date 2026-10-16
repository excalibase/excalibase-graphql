package io.github.excalibase.mysql;

import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlColumnTypesTest {

    @Test
    @DisplayName("tinyint(1) is a boolean, a wider tinyint stays a number")
    void tinyintOneIsBoolean() {
        SchemaInfo info = new SchemaInfo();

        MysqlColumnTypes.register(info, "gadget", "in_stock", "tinyint", "tinyint(1)");
        MysqlColumnTypes.register(info, "gadget", "rank", "tinyint", "tinyint unsigned");

        assertThat(info.getColumnType("gadget", "in_stock")).isEqualTo("boolean");
        assertThat(info.getColumnType("gadget", "rank")).isEqualTo("tinyint");
    }

    @Test
    @DisplayName("datetime has no zone and timestamp has one, as Postgres names them")
    void datetimeAndTimestamp() {
        SchemaInfo info = new SchemaInfo();

        MysqlColumnTypes.register(info, "gadget", "made_at", "datetime", "datetime");
        MysqlColumnTypes.register(info, "gadget", "seen_at", "timestamp", "timestamp(3)");

        assertThat(info.getColumnType("gadget", "made_at")).isEqualTo("timestamp");
        assertThat(info.getColumnType("gadget", "seen_at")).isEqualTo("timestamptz");
    }

    @Test
    @DisplayName("an ENUM column is an enum of its labels, named after its table and column")
    void enumColumnIsAnEnum() {
        SchemaInfo info = new SchemaInfo();

        MysqlColumnTypes.register(info, "gadget", "size", "enum", "enum('small','large')");

        assertThat(info.getEnumType("gadget", "size")).isEqualTo("gadget_size_enum");
        assertThat(info.getEnumTypes().get("gadget_size_enum")).containsExactly("small", "large");
    }

    @Test
    @DisplayName("an ENUM whose labels are not GraphQL names stays a string")
    void enumWithInvalidLabelsStaysAString() {
        SchemaInfo info = new SchemaInfo();

        MysqlColumnTypes.register(info, "task", "state", "enum", "enum('in progress','done')");
        MysqlColumnTypes.register(info, "task", "flag", "enum", "enum('true','false')");

        assertThat(info.getEnumType("task", "state")).isNull();
        assertThat(info.getEnumType("task", "flag")).isNull();
        assertThat(info.getColumnType("task", "state")).isEqualTo("varchar");
    }

    @Test
    @DisplayName("enum labels keep commas inside quotes and doubled quotes")
    void enumLabelsAreParsed() {
        assertThat(MysqlColumnTypes.enumLabels("enum('a,b','it''s','c')"))
                .contains(List.of("a,b", "it's", "c"));
        assertThat(MysqlColumnTypes.enumLabels("varchar(10)")).isEqualTo(Optional.empty());
        assertThat(MysqlColumnTypes.enumLabels(null)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("other types are kept as MySQL reports them")
    void otherTypesAreKept() {
        SchemaInfo info = new SchemaInfo();

        MysqlColumnTypes.register(info, "gadget", "name", "VARCHAR", null);

        assertThat(info.getColumnType("gadget", "name")).isEqualTo("varchar");
    }
}
