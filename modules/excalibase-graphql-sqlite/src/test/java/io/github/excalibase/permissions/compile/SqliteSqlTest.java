package io.github.excalibase.permissions.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSqlTest {

    private final SqliteSql sql = new SqliteSql();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"A%|A*", "_eta|?eta", "a\\%b|a%b", "a\\_b|a_b", "%\\_%|*_*", "x*y|x[*]y",
            "what?|what[?]", "[a]|[[]a]", "a\\\\b|a\\b"})
    void likePattern_becomesTheGlobThatMatchesTheSame(String like, String glob) {
        assertThat(sql.likePattern(like)).isEqualTo(glob);
    }

    @Test
    void like_isGlob_soCaseSensitivityDoesNotDependOnThePragma() {
        assertThat(sql.like("t.\"title\"", ":p", false)).isEqualTo("t.\"title\" GLOB :p");
        assertThat(sql.like("t.\"title\"", ":p", true)).isEqualTo("t.\"title\" NOT GLOB :p");
    }

    @Test
    void imageRow_namesItsValuesInsideTheDerivedTable() {
        assertThat(sql.imageRow("img", List.of(":a", ":b"), List.of("x", "y")))
                .isEqualTo(" FROM (SELECT :a AS x, :b AS y) AS img");
    }

    @Test
    void declaredTypes_mapToTheKindsPermissionsCompareAs() {
        assertThat(SqliteSql.kindOf("INTEGER")).isEqualTo(ColumnType.Kind.BIGINT);
        assertThat(SqliteSql.kindOf("int")).isEqualTo(ColumnType.Kind.INTEGER);
        assertThat(SqliteSql.kindOf("VARCHAR(20)")).isEqualTo(ColumnType.Kind.TEXT);
        assertThat(SqliteSql.kindOf("TIMESTAMPTZ")).isEqualTo(ColumnType.Kind.TIMESTAMPTZ);
        assertThat(SqliteSql.kindOf("BLOB")).isNull();
        assertThat(SqliteSql.kindOf("")).isNull();
    }

    @Test
    void quote_doublesAQuoteInsideTheName() {
        assertThat(sql.quote("plain")).isEqualTo("\"plain\"");
        assertThat(sql.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(sql.quote("\"; DROP TABLE t; --")).isEqualTo("\"\"\"; DROP TABLE t; --\"");
    }

    @Test
    void namesSQLiteTreatsAsTextOrIntegers_areComparedAsThose() {
        assertThat(SqliteSql.kindOf("NVARCHAR(30)")).isEqualTo(ColumnType.Kind.TEXT);
        assertThat(SqliteSql.kindOf("native character")).isEqualTo(ColumnType.Kind.TEXT);
        assertThat(SqliteSql.kindOf("unsigned int")).isEqualTo(ColumnType.Kind.BIGINT);
    }

    @Test
    void storedTimestamp_readsSQLitesOwnDefaultInTheFormTheEngineWrites() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (at TEXT)");
            statement.execute("INSERT INTO t VALUES ('2026-01-01 10:00:00'), ('2026-01-01 10:00:00.250'),"
                    + " ('2026-01-01T10:00:00.500000Z')");
            try (ResultSet rows = statement.executeQuery("SELECT " + SqliteSql.storedTimestamp("at", true)
                    + " FROM t ORDER BY rowid")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("2026-01-01T10:00:00.000000Z");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("2026-01-01T10:00:00.250000Z");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("2026-01-01T10:00:00.500000Z");
            }
        }
    }
}
