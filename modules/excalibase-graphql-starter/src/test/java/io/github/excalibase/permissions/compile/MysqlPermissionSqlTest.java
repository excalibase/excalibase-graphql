package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.compile.RowPredicate.Outcome;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.ColumnDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shape of the MySQL SQL a permission compiles to; row semantics are pinned by the MySQL differential test. */
class MysqlPermissionSqlTest {

    private static final SchemaInfo SCHEMA = MysqlFixtureSchema.schema();
    private static final SessionBinding BINDING = new SessionBinding(Map.of("x-excalibase-user-id", U1));

    private static SqlFragment compile(String json) {
        return FilterCompiler.compile(MYSQL, parse(json), NOTES, "n", SCHEMA, BINDING, new ParamNamer("perm"));
    }

    @Test
    void identifiersAreQuotedWithBackticks_andTextBindsAsText() {
        SqlFragment fragment = compile("{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}");

        assertThat(fragment.sql()).isEqualTo("n.`owner_id` = :perm_p0");
        assertThat(fragment.params()).containsExactly(Map.entry("perm_p0", U1));
    }

    @Test
    void anAliasTheMysqlDialectQuoted_isAccepted_andADoubleQuotedOneIsNot() {
        String json = "{\"id\": {\"_eq\": 1}}";

        assertThat(FilterCompiler.compile(MYSQL, parse(json), NOTES, "`ab12cd34`", SCHEMA, BINDING,
                new ParamNamer("perm")).sql()).isEqualTo("`ab12cd34`.`id` = :perm_p0");
        assertThatThrownBy(() -> FilterCompiler.compile(MYSQL, parse(json), NOTES, "\"ab12cd34\"", SCHEMA, BINDING,
                new ParamNamer("perm"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void relationship_isAnExistsOverTheBacktickQuotedTable() {
        SqlFragment fragment = compile("{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}");

        assertThat(fragment.sql()).isEqualTo("EXISTS (SELECT 1 FROM `public`.`orgs` perm_a0 WHERE"
                + " perm_a0.`id` = n.`org_id` AND perm_a0.`name` = :perm_p0)");
    }

    @Test
    void tinyintOne_isABoolean_andAPlainTinyintIsNot() {
        SqlFragment fragment = compile("{\"pinned\": {\"_eq\": true}}");

        assertThat(fragment.sql()).isEqualTo("n.`pinned` = :perm_p0");
        assertThat(fragment.params()).containsEntry("perm_p0", true);
        assertThatThrownBy(() -> compile("{\"level\": {\"_eq\": true}}"))
                .extracting("code").isEqualTo(PermissionEvaluationException.INVALID_LITERAL);
    }

    @Test
    void timestamp_comparesAsUtc_whateverTheSessionTimeZone() {
        SqlFragment fragment = compile("{\"created_at\": {\"_gt\": \"2026-01-01T10:00:00+02:00\"}}");

        assertThat(fragment.sql())
                .isEqualTo("CAST(n.`created_at` AT TIME ZONE 'UTC' AS DATETIME(6)) > CAST(:perm_p0 AS DATETIME(6))");
        assertThat(fragment.params()).containsEntry("perm_p0", LocalDateTime.of(2026, 1, 1, 8, 0));
    }

    @Test
    void datetimeAndDate_compareAsCastValues() {
        assertThat(compile("{\"local_at\": {\"_gte\": \"2026-01-01\"}}").sql())
                .isEqualTo("n.`local_at` >= CAST(:perm_p0 AS DATETIME(6))");
        assertThat(compile("{\"due\": {\"_lt\": \"2026-06-01\"}}").params())
                .containsEntry("perm_p0", LocalDate.of(2026, 6, 1));
    }

    @Test
    void enum_comparesByDeclarationOrder_likePostgres() {
        SqlFragment fragment = compile("{\"mood\": {\"_gt\": \"sad\"}}");

        assertThat(fragment.sql()).isEqualTo("(n.`mood` + 0) > :perm_p0");
        assertThat(fragment.params()).containsEntry("perm_p0", 1);
        assertThatThrownBy(() -> compile("{\"mood\": {\"_eq\": \"SAD\"}}"))
                .extracting("code").isEqualTo(PermissionEvaluationException.INVALID_LITERAL);
    }

    @Test
    void float_comparesAtSinglePrecision() {
        assertThat(compile("{\"ratio\": {\"_eq\": 0.1}}").sql()).isEqualTo("n.`ratio` = CAST(:perm_p0 AS FLOAT)");
        assertThat(compile("{\"score\": {\"_eq\": 0.1}}").sql()).isEqualTo("n.`score` = CAST(:perm_p0 AS DOUBLE)");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"score\": {\"_lt\": \"NaN\"}}|(n.`score` = n.`score`)",
            "{\"score\": {\"_lte\": \"Infinity\"}}|(n.`score` = n.`score`)",
            "{\"score\": {\"_neq\": \"NaN\"}}|(n.`score` = n.`score`)",
            "{\"score\": {\"_gt\": \"-Infinity\"}}|(n.`score` = n.`score`)",
            "{\"score\": {\"_eq\": \"NaN\"}}|(n.`score` <> n.`score`)",
            "{\"score\": {\"_gte\": \"NaN\"}}|(n.`score` <> n.`score`)",
            "{\"ratio\": {\"_lt\": \"-Infinity\"}}|(n.`ratio` <> n.`ratio`)",
            "{\"score\": {\"_in\": [\"NaN\"]}}|(n.`score` <> n.`score`)",
            "{\"score\": {\"_nin\": [\"NaN\", \"Infinity\"]}}|(n.`score` = n.`score`)",
            "{\"score\": {\"_in\": [\"NaN\", 1.5]}}|n.`score` IN (CAST(:perm_p0 AS DOUBLE))"})
    void nonFiniteFloats_whichMysqlCannotStore_keepPostgresOrderAndNulls(String json, String sql) {
        assertThat(compile(json).sql()).isEqualTo(sql);
    }

    @Test
    void unsigned_widensTheRange() {
        assertThat(compile("{\"small\": {\"_eq\": 4294967295}}").params()).containsEntry("perm_p0", 4294967295L);
        assertThat(compile("{\"big\": {\"_eq\": \"18446744073709551615\"}}").params())
                .containsEntry("perm_p0", new BigDecimal("18446744073709551615"));
    }

    @Test
    void aTypeWithNoMysqlMapping_canOnlyBeTestedForNull() {
        assertThat(compile("{\"meta\": {\"_is_null\": true}}").sql()).isEqualTo("n.`meta` IS NULL");
        assertThatThrownBy(() -> compile("{\"meta\": {\"_eq\": \"x\"}}"))
                .extracting("code").isEqualTo(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE);
        assertThatThrownBy(() -> ExpressionCheck.check(MYSQL, parse("{\"meta\": {\"_eq\": \"X-Excalibase-User-Id\"}}"),
                NOTES, SCHEMA)).extracting("code").isEqualTo(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE);
    }

    @Test
    void aColumnTheLoaderDidNotDeclare_isNotGuessed() {
        SchemaInfo undeclared = MysqlFixtureSchema.schema();
        undeclared.addColumn(NOTES, "extra", "int");

        assertThatThrownBy(() -> FilterCompiler.compile(MYSQL, parse("{\"extra\": {\"_eq\": 1}}"), NOTES, "n",
                undeclared, BINDING, new ParamNamer("perm")))
                .extracting("code").isEqualTo(PermissionEvaluationException.UNSUPPORTED_COLUMN_TYPE);
    }

    @Test
    void aWriteReadingItsOwnTable_readsItThroughADerivedTable() {
        String json = "{\"_exists\": {\"_table\": \"public.notes\", \"_where\": {\"status\": {\"_eq\": \"open\"}}}}";

        assertThat(FilterCompiler.compileForWrite(MYSQL, parse(json), NOTES, "n", SCHEMA, BINDING,
                new ParamNamer("perm")).sql()).isEqualTo("EXISTS (SELECT 1 FROM (SELECT * FROM `public`.`notes`)"
                + " perm_a0 WHERE perm_a0.`status` = :perm_p0)");
        assertThat(FilterCompiler.compileForWrite(MYSQL, parse("{\"publicOrgId\": {}}"), NOTES, "n", SCHEMA,
                BINDING, new ParamNamer("perm")).sql())
                .isEqualTo("EXISTS (SELECT 1 FROM `public`.`orgs` perm_a0 WHERE perm_a0.`id` = n.`org_id` AND TRUE)");
        assertThat(compile(json).sql()).startsWith("EXISTS (SELECT 1 FROM `public`.`notes` perm_a0");
    }

    @Test
    void postgres_readsItsOwnTableDirectly_evenInAWrite() {
        String json = "{\"_exists\": {\"_table\": \"public.notes\", \"_where\": {}}}";

        assertThat(FilterCompiler.compileForWrite(PermissionDialect.POSTGRES, parse(json), NOTES, "n",
                FixtureSchema.schema(), BINDING, new ParamNamer("perm")).sql())
                .isEqualTo("EXISTS (SELECT 1 FROM \"public\".\"notes\" perm_a0 WHERE TRUE)");
    }

    @Test
    void keyProbe_isOverTheLiveRowByItsKey() {
        SqlFragment probe = RowProbe.build(MYSQL, parse("{\"status\": {\"_eq\": \"open\"}}"), NOTES, Map.of("id", 4),
                SCHEMA, BINDING, new ParamNamer("probe")).orElseThrow();

        assertThat(probe.sql()).isEqualTo("SELECT EXISTS (SELECT 1 FROM `public`.`notes` probe_a0 WHERE"
                + " probe_a0.`id` = :probe_p0 AND (probe_a0.`status` = :probe_p1))");
    }

    @Test
    void imageProbe_isAValuesRowTypedLikeTheColumns() {
        String filter = "{\"_and\": [{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}},"
                + " {\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}]}";
        Map<String, Object> image = Map.of("id", 7, "owner_id", U1, "org_id", "1");

        SqlFragment probe = RowProbe.ofImage(MYSQL, parse(filter), NOTES, image, SCHEMA, BINDING,
                new ParamNamer("probe")).orElseThrow();

        assertThat(probe.sql()).isEqualTo("SELECT EXISTS (SELECT 1 FROM (VALUES ROW(CONVERT(:probe_p0 USING utf8mb4)"
                + " COLLATE utf8mb4_bin, CAST(:probe_p1 AS SIGNED))) AS probe_a0 (`owner_id`, `org_id`) WHERE"
                + " (probe_a0.`owner_id` = :probe_p2 AND EXISTS (SELECT 1 FROM `public`.`orgs` probe_a1"
                + " WHERE probe_a1.`id` = probe_a0.`org_id` AND probe_a1.`name` = :probe_p3)))");
        assertThat(probe.params()).containsEntry("probe_p1", 1L);
    }

    @Test
    void imageProbe_holdsTimestampsAsUtc_andEnumsByPosition() {
        Map<String, Object> image = Map.of("created_at", "2026-01-01T10:00:00Z", "mood", "happy");

        SqlFragment probe = RowProbe.ofImage(MYSQL, parse("{\"_and\": [{\"created_at\": {\"_gt\": \"2025-01-01T00:00:00Z\"}},"
                + " {\"mood\": {\"_eq\": \"happy\"}}]}"), NOTES, image, SCHEMA, BINDING, new ParamNamer("probe"))
                .orElseThrow();

        assertThat(probe.sql()).isEqualTo("SELECT EXISTS (SELECT 1 FROM (VALUES ROW(CAST(:probe_p0 AS DATETIME(6)),"
                + " CAST(:probe_p1 AS SIGNED))) AS probe_a0 (`created_at`, `mood`) WHERE"
                + " (probe_a0.`created_at` > CAST(:probe_p2 AS DATETIME(6)) AND (probe_a0.`mood` + 0) = :probe_p3))");
        assertThat(probe.params()).containsEntry("probe_p0", LocalDateTime.of(2026, 1, 1, 10, 0))
                .containsEntry("probe_p1", 3).containsEntry("probe_p3", 3);
    }

    @Test
    void imageProbe_ofTextWithNoKnownCollation_isNotGuessed() {
        SchemaInfo undeclared = MysqlFixtureSchema.schema();
        undeclared.addColumnDeclaration(NOTES, "status", new ColumnDeclaration("varchar(20)", null, null));

        assertThat(RowProbe.ofImage(MYSQL, parse("{\"status\": {\"_eq\": \"open\"}}"), NOTES, Map.of("status", "open"),
                undeclared, BINDING, new ParamNamer("probe"))).isEmpty();
    }

    @Test
    void imageProbe_withNoColumnRead_needsNoValuesRow() {
        SqlFragment probe = RowProbe.ofImage(MYSQL, parse("{\"_exists\": {\"_table\": \"public.orgs\", \"_where\": {}}}"),
                NOTES, Map.of(), SCHEMA, BINDING, new ParamNamer("probe")).orElseThrow();

        assertThat(probe.sql()).isEqualTo(
                "SELECT EXISTS (SELECT 1 WHERE EXISTS (SELECT 1 FROM `public`.`orgs` probe_a1 WHERE TRUE))");
    }

    @Test
    void inMemory_textIsLeftToTheDatabase_becauseItsCollationDecides() {
        Map<String, Object> row = new HashMap<>(Map.of("status", "open", "title", "Alpha", "id", 1));

        assertThat(predicate("{\"status\": {\"_eq\": \"open\"}}").test(row)).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(predicate("{\"status\": {\"_in\": [\"open\"]}}").test(row)).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(predicate("{\"title\": {\"_like\": \"A%\"}}").test(row)).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(predicate("{\"id\": {\"_eq\": 1}}").test(row)).isEqualTo(Outcome.MATCH);
        assertThat(predicate("{\"_and\": [{\"id\": {\"_eq\": 2}}, {\"status\": {\"_eq\": \"open\"}}]}").test(row))
                .isEqualTo(Outcome.NO_MATCH);
    }

    @Test
    void inMemory_booleansAndEnumsAreReadAsMysqlDeliversThem() {
        assertThat(predicate("{\"pinned\": {\"_eq\": true}}").test(Map.of("pinned", 1))).isEqualTo(Outcome.MATCH);
        assertThat(predicate("{\"pinned\": {\"_eq\": true}}").test(Map.of("pinned", 0))).isEqualTo(Outcome.NO_MATCH);
        assertThat(predicate("{\"pinned\": {\"_eq\": true}}").test(Map.of("pinned", 2)))
                .isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(predicate("{\"mood\": {\"_eq\": \"ok\"}}").test(Map.of("mood", "ok"))).isEqualTo(Outcome.MATCH);
        assertThat(predicate("{\"mood\": {\"_eq\": \"ok\"}}").test(Map.of("mood", 2))).isEqualTo(Outcome.MATCH);
    }

    @Test
    void trackedFunctions_areNotOfferedOnMysql() {
        assertThat(MYSQL.tracksFunctions()).isFalse();
        assertThat(PermissionDialect.POSTGRES.tracksFunctions()).isTrue();
    }

    @Test
    void compositeKeys_bindEachColumn() {
        SqlFragment probe = RowProbe.build(MYSQL, parse("{\"role\": {\"_eq\": \"admin\"}}"), MEMBERS,
                Map.of("org_id", 1, "user_id", U1), SCHEMA, BINDING, new ParamNamer("probe")).orElseThrow();

        assertThat(probe.sql()).contains("probe_a0.`org_id` = :probe_p0 AND probe_a0.`user_id` = :probe_p1");
    }

    private static RowPredicate predicate(String json) {
        return RowPredicate.compile(MYSQL, parse(json), NOTES, SCHEMA, BINDING);
    }
}
