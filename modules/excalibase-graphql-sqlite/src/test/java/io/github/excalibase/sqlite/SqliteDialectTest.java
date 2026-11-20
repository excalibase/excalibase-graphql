package io.github.excalibase.sqlite;

import io.github.excalibase.DialectFeature;
import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;
import io.github.excalibase.errors.DataErrorException;
import io.github.excalibase.permissions.compile.PermissionDialect;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteDialectTest {

    private final SqliteDialect dialect = new SqliteDialect();

    @Test
    void identifiers_areDoubleQuotedAndEscaped() {
        assertThat(dialect.quoteIdentifier("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(dialect.qualifiedTable("main", "t")).isEqualTo("\"main\".\"t\"");
    }

    @Test
    void objects_andArrays_useSqliteJsonFunctions() {
        assertThat(dialect.buildObject(List.of("'a'", "x"))).isEqualTo("json_object('a', x)");
        assertThat(dialect.aggregateArray("o")).isEqualTo("json_group_array(json(o))");
        assertThat(dialect.coalesceArray("a")).isEqualTo("json(COALESCE(a, '[]'))");
        assertThat(dialect.jsonArray(List.of("a", "b"))).isEqualTo("json_array(a, b)");
    }

    @Test
    void upsert_isOnConflict_orDoNothingWithoutColumns() {
        assertThat(dialect.onConflict(List.of("id"), List.of("name", "email")))
                .isEqualTo(" ON CONFLICT (\"id\") DO UPDATE SET \"name\" = excluded.\"name\", \"email\" = excluded.\"email\"");
        assertThat(dialect.onConflict(List.of("id"), List.of())).isEqualTo(" ON CONFLICT (\"id\") DO NOTHING");
    }

    @Test
    void updateAndDelete_aliasTheTableWithAs() {
        assertThat(dialect.cteUpdate("a", "\"t\"", "\"x\" = :p", " WHERE a.id = 1", "")).isEqualTo(
                "UPDATE \"t\" AS a SET \"x\" = :p WHERE a.id = 1");
        assertThat(dialect.cteDelete("a", "\"t\"", " WHERE a.id = 1", "")).isEqualTo("DELETE FROM \"t\" AS a WHERE a.id = 1");
    }

    @Test
    void ilike_lowerCasesBothSides_andNullsOrderNatively() {
        assertThat(dialect.ilike("c", ":p")).isEqualTo("LOWER(c) LIKE LOWER(:p)");
        assertThat(dialect.orderByNulls("c", "DESC", "last")).isEqualTo("c DESC NULLS LAST");
    }

    @Test
    void selectColumn_rendersTypesAsTheApiDoes() {
        assertThat(dialect.selectColumn("c", "bigint")).isEqualTo("CAST(c AS TEXT)");
        assertThat(dialect.selectColumn("c", "boolean")).startsWith("json(CASE WHEN c THEN 'true'");
        assertThat(dialect.selectColumn("c", "bytea")).contains("lower(hex(c))");
        assertThat(dialect.selectColumn("c", "text")).isEqualTo("c");
    }

    @Test
    void bindValue_storesTimestampsAsFixedWidthUtcText() {
        assertThat(dialect.bindValue("timestamptz", "2026-03-01T12:30:00+02:00")).isEqualTo("2026-03-01T10:30:00.000000Z");
        assertThat(dialect.bindValue("timestamptz", "2026-03-01 10:30:00")).isEqualTo("2026-03-01T10:30:00.000000Z");
        assertThat(dialect.bindValue("timestamp", "2026-03-01T10:30:00.5")).isEqualTo("2026-03-01T10:30:00.500000");
        assertThat(dialect.bindValue("date", "2026-03-01")).isEqualTo("2026-03-01");
        assertThat(dialect.bindValue("bytea", "\\x0aff")).isEqualTo(new byte[] {10, (byte) 255});
        assertThat(dialect.bindValue("bigint", new BigInteger("5"))).isEqualTo(5L);
    }

    @Test
    void bindValue_refusesWhatTheTypeCannotHold() {
        assertThatThrownBy(() -> dialect.bindValue("timestamptz", "yesterday")).isInstanceOf(DataErrorException.class);
        assertThatThrownBy(() -> dialect.bindValue("date", "nope")).isInstanceOf(DataErrorException.class);
        assertThatThrownBy(() -> dialect.bindValue("bytea", "\\xzz")).isInstanceOf(DataErrorException.class);
    }

    @Test
    void cursor_roundTripsThroughHex() {
        assertThat(dialect.decodeCursor("5B315D")).isEqualTo("[1]".getBytes());
    }

    @Test
    void capabilities_offerNoRegexJsonOrFullText() {
        FilterCapabilities capabilities = dialect.filterCapabilities();

        assertThat(capabilities.operators(FilterColumnKind.STRING)).contains("like", "ilike", "in", "isNull")
                .doesNotContain("regex", "iregex");
        assertThat(capabilities.operators(FilterColumnKind.JSON)).containsExactly("isNull", "isNotNull");
        assertThat(capabilities.operators(FilterColumnKind.TSVECTOR)).isEmpty();
        assertThat(capabilities.restOperators()).doesNotContain("match", "fts", "cs");
        assertThat(capabilities.unsupportedReason(FilterColumnKind.STRING, "regex")).contains("regular expression");
    }

    @Test
    void permissionsCompileForSqlite_andFeaturesAreDeclared() {
        assertThat(dialect.permissionDialect()).isEqualTo(PermissionDialect.SQLITE);
        assertThat(dialect.features()).containsExactlyInAnyOrder(DialectFeature.AGGREGATES, DialectFeature.UPSERT);
        assertThat(dialect.supportsReturning()).isFalse();
    }
}
