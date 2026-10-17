package io.github.excalibase.mysql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlDialectTest {

    private final MysqlDialect dialect = new MysqlDialect();

    @Test
    @DisplayName("a boolean column renders as a JSON boolean, null kept")
    void booleanColumn() {
        assertThat(dialect.selectColumn("a.`b`", "boolean")).isEqualTo(
                "IF(a.`b` IS NULL, NULL, IF(a.`b`, CAST('true' AS JSON), CAST('false' AS JSON)))");
    }

    @Test
    @DisplayName("a timestamp with zone renders in UTC with its offset")
    void timestampColumnIsUtc() {
        String sql = dialect.selectColumn("a.`t`", "timestamptz");

        assertThat(sql).contains("CONVERT_TZ(a.`t`, @@session.time_zone, '+00:00')").endsWith(", '+00:00')");
    }

    @Test
    @DisplayName("a datetime renders in ISO 8601 without an offset")
    void datetimeColumn() {
        assertThat(dialect.selectColumn("a.`t`", "timestamp"))
                .startsWith("CONCAT(DATE_FORMAT(a.`t`, '%Y-%m-%dT%H:%i:%s')")
                .doesNotContain("CONVERT_TZ");
    }

    @Test
    @DisplayName("other columns keep their suffix cast, and an unknown type none")
    void otherColumns() {
        assertThat(dialect.selectColumn("a.`n`", "bigint")).isEqualTo("a.`n`+0");
        assertThat(dialect.selectColumn("a.`n`", "varchar")).isEqualTo("a.`n`");
        assertThat(dialect.selectColumn("a.`n`", null)).isEqualTo("a.`n`");
    }

    @Test
    @DisplayName("a UTC datetime sent with Z is bound with the offset MySQL reads")
    void zuluIsBoundAsOffset() {
        assertThat(dialect.bindValue("timestamptz", "2026-03-04T05:06:07.5Z")).isEqualTo("2026-03-04T05:06:07.5+00:00");
        assertThat(dialect.bindValue("timestamp", "2026-03-04T05:06:07z")).isEqualTo("2026-03-04T05:06:07+00:00");
        assertThat(dialect.bindValue("timestamp", "2026-03-04T05:06:07")).isEqualTo("2026-03-04T05:06:07");
        assertThat(dialect.bindValue("varchar", "Z")).isEqualTo("Z");
        assertThat(dialect.bindValue("timestamp", 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("an ENUM column answers its label as stored")
    void enumAnswersItsLabel() {
        assertThat(dialect.enumToText("a.`size`")).isEqualTo("a.`size`");
    }
}
