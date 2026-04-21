package io.github.excalibase.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlUtilsTest {

    @ParameterizedTest(name = "sqlTypeFor(\"{0}\") → Types.{1}")
    @CsvSource({
            "integer, BIGINT",
            "int4, BIGINT",
            "bigint, BIGINT",
            "smallint, BIGINT",
            "numeric, NUMERIC",
            "decimal, NUMERIC",
            "float8, DOUBLE",
            "double precision, DOUBLE",
            "real, DOUBLE",
            "boolean, BOOLEAN",
            "bool, BOOLEAN",
            "text, VARCHAR",
            "varchar(10), VARCHAR",
            "character varying, VARCHAR",
            "jsonb, OTHER",
            "uuid, OTHER"
    })
    @DisplayName("sqlTypeFor maps Postgres types to the closest JDBC Types constant")
    void sqlTypeFor_mapsAllKnownTypes(String pgType, String expected) {
        int expectedCode = switch (expected) {
            case "BIGINT" -> Types.BIGINT;
            case "NUMERIC" -> Types.NUMERIC;
            case "DOUBLE" -> Types.DOUBLE;
            case "BOOLEAN" -> Types.BOOLEAN;
            case "VARCHAR" -> Types.VARCHAR;
            default -> Types.OTHER;
        };
        assertThat(SqlUtils.sqlTypeFor(pgType)).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("sqlTypeFor with null pgType returns Types.OTHER")
    void sqlTypeFor_null_returnsOther() {
        assertThat(SqlUtils.sqlTypeFor(null)).isEqualTo(Types.OTHER);
    }

    @Test
    @DisplayName("sqlTypeFor is case-insensitive")
    void sqlTypeFor_caseInsensitive() {
        assertThat(SqlUtils.sqlTypeFor("INTEGER")).isEqualTo(Types.BIGINT);
        assertThat(SqlUtils.sqlTypeFor("BOOLEAN")).isEqualTo(Types.BOOLEAN);
    }

    @Test
    @DisplayName("resolveNamedParams substitutes simple :name references with ?")
    void resolveNamedParams_singleParam() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT * FROM users WHERE id = :id", Map.of("id", 42));

        assertThat(resolved.sql()).isEqualTo("SELECT * FROM users WHERE id = ?");
        assertThat(resolved.values()).containsExactly(42);
    }

    @Test
    @DisplayName("resolveNamedParams substitutes multiple :name references in order")
    void resolveNamedParams_multipleParams() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT * FROM t WHERE a = :a AND b = :b",
                Map.of("a", 1, "b", "two"));

        assertThat(resolved.sql()).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ?");
        assertThat(resolved.values()).containsExactly(1, "two");
    }

    @Test
    @DisplayName("resolveNamedParams preserves a ::cast suffix after a resolved param")
    void resolveNamedParams_paramWithCast() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "INSERT INTO t VALUES (:id::bigint)", Map.of("id", 7L));

        assertThat(resolved.sql()).isEqualTo("INSERT INTO t VALUES (?::bigint)");
        assertThat(resolved.values()).containsExactly(7L);
    }

    @Test
    @DisplayName("resolveNamedParams preserves ::jsonb cast")
    void resolveNamedParams_jsonbCast() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "UPDATE t SET data = :d::jsonb WHERE id = :id",
                Map.of("d", "{}", "id", 1));

        assertThat(resolved.sql()).isEqualTo("UPDATE t SET data = ?::jsonb WHERE id = ?");
        assertThat(resolved.values()).containsExactly("{}", 1);
    }

    @Test
    @DisplayName("resolveNamedParams leaves unknown :name references untouched")
    void resolveNamedParams_unknownName_leftIntact() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT :known, :unknown", Map.of("known", 1));

        assertThat(resolved.sql()).contains("?").contains(":unknown");
        assertThat(resolved.values()).containsExactly(1);
    }

    @Test
    @DisplayName("resolveNamedParams does not touch :name inside single-quoted strings")
    void resolveNamedParams_quotedStringsNotSubstituted() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT ':id' FROM t WHERE x = :id",
                Map.of("id", 99));

        assertThat(resolved.sql()).isEqualTo("SELECT ':id' FROM t WHERE x = ?");
        assertThat(resolved.values()).containsExactly(99);
    }

    @Test
    @DisplayName("resolveNamedParams handles identifiers with underscores and digits")
    void resolveNamedParams_underscoresAndDigits() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT :user_id_1, :k2",
                Map.of("user_id_1", 1, "k2", 2));

        assertThat(resolved.sql()).isEqualTo("SELECT ?, ?");
        assertThat(resolved.values()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("resolveNamedParams returns SQL unchanged when params map is empty")
    void resolveNamedParams_emptyParams_noSubstitution() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT 1", Map.of());

        assertThat(resolved.sql()).isEqualTo("SELECT 1");
        assertThat(resolved.values()).isEmpty();
    }

    @Test
    @DisplayName("resolveNamedParams treats ':' not followed by a letter as literal text")
    void resolveNamedParams_colonWithoutLetter_literal() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT ':' AS x", Map.of());

        assertThat(resolved.sql()).isEqualTo("SELECT ':' AS x");
    }

    @Test
    @DisplayName("resolveNamedParams handles unterminated single quotes gracefully")
    void resolveNamedParams_unterminatedQuote_doesNotThrow() {
        SqlUtils.ResolvedSql resolved = SqlUtils.resolveNamedParams(
                "SELECT 'oops :id", Map.of("id", 1));

        assertThat(resolved.sql()).startsWith("SELECT 'oops :id");
    }
}
