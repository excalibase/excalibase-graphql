package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.compile.RowPredicate.Outcome;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The in-memory matcher realtime uses: SQL three-valued logic, and an honest "ask the database". */
class RowPredicateTest {

    private static final SchemaInfo SCHEMA = FixtureSchema.schema();
    private static final SessionBinding BINDING = new SessionBinding(Map.of("x-excalibase-user-id", U1));

    private static Outcome test(String json, Map<String, Object> row) {
        return RowPredicate.compile(parse(json), NOTES, SCHEMA, BINDING).test(row);
    }

    private static Map<String, Object> row(Object... namesAndValues) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            row.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return row;
    }

    @Test
    void comparisonAgainstNull_isNotAMatch_andNeitherIsItsNegation() {
        Map<String, Object> nullStatus = row("status", null);

        assertThat(test("{\"status\": {\"_eq\": \"open\"}}", nullStatus)).isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"status\": {\"_neq\": \"open\"}}", nullStatus)).isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"_not\": {\"status\": {\"_eq\": \"open\"}}}", nullStatus)).isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"status\": {\"_nin\": [\"a\"]}}", nullStatus)).isEqualTo(Outcome.NO_MATCH);
    }

    @Test
    void unknownOrTrue_isAMatch_andUnknownAndFalse_isNot() {
        Map<String, Object> nullStatus = row("status", null, "id", 1);

        assertThat(test("{\"_or\": [{\"status\": {\"_eq\": \"x\"}}, {\"id\": {\"_eq\": 1}}]}", nullStatus))
                .isEqualTo(Outcome.MATCH);
        assertThat(test("{\"_not\": {\"_and\": [{\"status\": {\"_eq\": \"x\"}}, {\"id\": {\"_eq\": 2}}]}}", nullStatus))
                .isEqualTo(Outcome.MATCH);
    }

    @Test
    void isNull_neverYieldsUnknown() {
        assertThat(test("{\"status\": {\"_is_null\": true}}", row("status", null))).isEqualTo(Outcome.MATCH);
        assertThat(test("{\"status\": {\"_is_null\": false}}", row("status", null))).isEqualTo(Outcome.NO_MATCH);
    }

    @ParameterizedTest(name = "{1} LIKE {0} -> {2}")
    @CsvSource(delimiter = '|', value = {
            "A%|Alpha|true", "A%|alpha|false", "_eta|beta|true", "_eta|zeta!|false",
            "a\\%b|a%b|true", "a\\%b|axb|false", "%\\_%|x_y|true", "%\\_%|xy|false",
            "%|''|true", "a.c|abc|false", "a*|aaa|false", "\\a|a|true"})
    void like_followsPostgresPatternRules(String pattern, String value, boolean expected) {
        String json = "{\"title\": {\"_like\": " + quoted(pattern) + "}}";

        assertThat(test(json, row("title", value.equals("''") ? "" : value)))
                .isEqualTo(expected ? Outcome.MATCH : Outcome.NO_MATCH);
    }

    @Test
    void likePatternEndingInEscape_isRefused() {
        assertThatThrownBy(() -> test("{\"title\": {\"_like\": \"a\\\\\"}}", row("title", "a")))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("invalid_like_pattern");
    }

    @Test
    void valuesCompareByColumnType_whateverShapeTheRowCarries() {
        String later = "{\"created_at\": {\"_gt\": \"2026-01-01T00:00:00Z\"}}";

        assertThat(test(later, row("created_at", "2026-03-01 12:30:00+02"))).isEqualTo(Outcome.MATCH);
        assertThat(test(later, row("created_at", Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")))))
                .isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"amount\": {\"_eq\": 10.5}}", row("amount", new BigDecimal("10.50")))).isEqualTo(Outcome.MATCH);
        assertThat(test("{\"amount\": {\"_eq\": 10.5}}", row("amount", "10.500"))).isEqualTo(Outcome.MATCH);
        assertThat(test("{\"org_id\": {\"_lt\": 10}}", row("org_id", 9))).isEqualTo(Outcome.MATCH);
        assertThat(test("{\"pinned\": {\"_eq\": true}}", row("pinned", "t"))).isEqualTo(Outcome.MATCH);
        assertThat(test("{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}", row("owner_id", UUID.fromString(U1))))
                .isEqualTo(Outcome.MATCH);
    }

    @Test
    void uuidsOrderAsUnsignedBytes_likePostgres() {
        String json = "{\"owner_id\": {\"_gt\": \"7fffffff-ffff-ffff-ffff-ffffffffffff\"}}";

        assertThat(test(json, row("owner_id", "80000000-0000-0000-0000-000000000000"))).isEqualTo(Outcome.MATCH);
    }

    @Test
    void relationshipAndExists_needTheDatabase() {
        assertThat(test("{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}", row("org_id", 1)))
                .isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test("{\"_exists\": {\"_table\": \"public.members\", \"_where\": {}}}", row()))
                .isEqualTo(Outcome.NEEDS_DATABASE);
    }

    @Test
    void aDecidedBranch_doesNotWaitForTheDatabase() {
        String ownerOrOrg = "{\"_or\": [{\"id\": {\"_eq\": 1}}, {\"publicOrgId\": {}}]}";
        String ownerAndOrg = "{\"_and\": [{\"id\": {\"_eq\": 1}}, {\"publicOrgId\": {}}]}";

        assertThat(test(ownerOrOrg, row("id", 1))).isEqualTo(Outcome.MATCH);
        assertThat(test(ownerOrOrg, row("id", 2))).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test(ownerAndOrg, row("id", 2))).isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"_not\": {\"publicOrgId\": {}}}", row("id", 2))).isEqualTo(Outcome.NEEDS_DATABASE);
    }

    @Test
    void collationAndTypeSpecificComparisons_needTheDatabase() {
        assertThat(test("{\"title\": {\"_gt\": \"b\"}}", row("title", "c"))).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test("{\"mood\": {\"_gt\": \"sad\"}}", row("mood", "ok"))).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test("{\"ip\": {\"_eq\": \"10.0.0.1\"}}", row("ip", "10.0.0.1"))).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test("{\"mood\": {\"_eq\": \"ok\"}}", row("mood", "ok"))).isEqualTo(Outcome.MATCH);
    }

    @Test
    void aColumnTheRowDoesNotCarry_needsTheDatabase() {
        assertThat(test("{\"status\": {\"_eq\": \"open\"}}", row("id", 1))).isEqualTo(Outcome.NEEDS_DATABASE);
        assertThat(test("{\"status\": {\"_is_null\": true}}", row("id", 1))).isEqualTo(Outcome.NEEDS_DATABASE);
    }

    @Test
    void aRowValueOfTheWrongShape_needsTheDatabase() {
        assertThat(test("{\"org_id\": {\"_eq\": 1}}", row("org_id", "one"))).isEqualTo(Outcome.NEEDS_DATABASE);
    }

    @Test
    void missingVariable_failsWhenThePredicateIsBuilt() {
        assertThatThrownBy(() -> RowPredicate.compile(parse("{\"publicOrgId\": {\"name\": {\"_eq\": \"X-Excalibase-Org\"}}}"),
                NOTES, SCHEMA, BINDING))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo("missing_session_variable");
    }

    @Test
    void unknownNames_failWhenThePredicateIsBuilt() {
        assertThatThrownBy(() -> RowPredicate.compile(parse("{\"nope\": {\"_eq\": 1}}"), NOTES, SCHEMA, BINDING))
                .extracting("code").isEqualTo("unknown_column");
        assertThatThrownBy(() -> RowPredicate.compile(parse("{\"nope\": {}}"), NOTES, SCHEMA, BINDING))
                .extracting("code").isEqualTo("unknown_relationship");
    }

    @Test
    void emptyLists_followTheSqlPath() {
        assertThat(test("{\"id\": {\"_in\": []}}", row("id", null))).isEqualTo(Outcome.NO_MATCH);
        assertThat(test("{\"id\": {\"_nin\": []}}", row("id", null))).isEqualTo(Outcome.MATCH);
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
