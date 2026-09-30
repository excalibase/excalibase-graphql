package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.BoolExp;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.github.excalibase.permissions.compile.FixtureSchema.MEMBERS;
import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.ORGS;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shape of the SQL a permission expression compiles to; row semantics are pinned by the differential test. */
class FilterCompilerTest {

    private static final SchemaInfo SCHEMA = FixtureSchema.schema();
    private static final SessionBinding BINDING = new SessionBinding(Map.of("x-excalibase-user-id", U1));

    private static SqlFragment compile(String json, String table) {
        return compile(parse(json), table, new ParamNamer("perm_c0"));
    }

    private static SqlFragment compile(BoolExp expression, String table, ParamNamer namer) {
        return FilterCompiler.compile(expression, table, "n", SCHEMA, BINDING, namer);
    }

    @Test
    void emptyExpression_isTrue() {
        assertThat(compile("{}", NOTES)).isEqualTo(new SqlFragment("TRUE", Map.of()));
    }

    @Test
    void comparison_bindsTheValueAsAParameter() {
        SqlFragment fragment = compile("{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}", NOTES);

        assertThat(fragment.sql()).isEqualTo("n.\"owner_id\" = :perm_c0_p0");
        assertThat(fragment.params()).containsExactly(Map.entry("perm_c0_p0", UUID.fromString(U1)));
    }

    @Test
    void textValuesNeverReachTheSql() {
        SqlFragment fragment = compile("{\"title\": {\"_eq\": \"x'); DROP TABLE notes; --\"}}", NOTES);

        assertThat(fragment.sql()).isEqualTo("n.\"title\" = :perm_c0_p0");
        assertThat(fragment.params()).containsValue("x'); DROP TABLE notes; --");
    }

    @Test
    void emptyIn_isFalse_andEmptyNin_isTrue() {
        assertThat(compile("{\"id\": {\"_in\": []}}", NOTES).sql()).isEqualTo("FALSE");
        assertThat(compile("{\"id\": {\"_nin\": []}}", NOTES).sql()).isEqualTo("TRUE");
    }

    @Test
    void inList_bindsOneParameterPerElement() {
        SqlFragment fragment = compile("{\"id\": {\"_in\": [1, 2]}}", NOTES);

        assertThat(fragment.sql()).isEqualTo("n.\"id\" IN (:perm_c0_p0, :perm_c0_p1)");
        assertThat(fragment.params()).containsEntry("perm_c0_p0", 1L).containsEntry("perm_c0_p1", 2L);
    }

    @Test
    void isNull_compilesWithoutParameters() {
        assertThat(compile("{\"status\": {\"_is_null\": true}}", NOTES).sql()).isEqualTo("n.\"status\" IS NULL");
        assertThat(compile("{\"status\": {\"_is_null\": false}}", NOTES).sql()).isEqualTo("n.\"status\" IS NOT NULL");
    }

    @Test
    void enumAndOtherTypes_castTheParameterToTheColumnType() {
        assertThat(compile("{\"mood\": {\"_eq\": \"ok\"}}", NOTES).sql())
                .isEqualTo("n.\"mood\" = CAST(:perm_c0_p0 AS \"public\".\"mood\")");
        assertThat(compile("{\"ip\": {\"_eq\": \"10.0.0.1\"}}", NOTES).sql())
                .isEqualTo("n.\"ip\" = CAST(:perm_c0_p0 AS inet)");
    }

    @Test
    void logic_isParenthesised_andEmptyOr_isFalse() {
        assertThat(compile("{\"_not\": {\"_or\": [{\"id\": {\"_eq\": 1}}, {\"id\": {\"_eq\": 2}}]}}", NOTES).sql())
                .isEqualTo("(NOT (n.\"id\" = :perm_c0_p0 OR n.\"id\" = :perm_c0_p1))");
        assertThat(compile("{\"_or\": []}", NOTES).sql()).isEqualTo("FALSE");
        assertThat(compile("{\"_and\": []}", NOTES).sql()).isEqualTo("TRUE");
    }

    @Test
    void forwardRelationship_joinsTheTargetToTheOuterAlias() {
        SqlFragment fragment = compile("{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}", NOTES);

        assertThat(fragment.sql()).isEqualTo("EXISTS (SELECT 1 FROM \"public\".\"orgs\" perm_c0_a0"
                + " WHERE perm_c0_a0.\"id\" = n.\"org_id\" AND perm_c0_a0.\"name\" = :perm_c0_p0)");
    }

    @Test
    void compositeRelationship_joinsEveryColumnPair() {
        SqlFragment fragment = compile("{\"publicMembers\": {}}", NOTES);

        assertThat(fragment.sql()).isEqualTo("EXISTS (SELECT 1 FROM \"public\".\"members\" perm_c0_a0"
                + " WHERE perm_c0_a0.\"org_id\" = n.\"org_id\" AND perm_c0_a0.\"user_id\" = n.\"owner_id\" AND TRUE)");
    }

    @Test
    void reverseRelationship_joinsTheChildToTheOuterAlias() {
        SqlFragment fragment = compile("{\"publicMembers\": {\"publicNotes\": {}}}", ORGS);

        assertThat(fragment.sql()).isEqualTo("EXISTS (SELECT 1 FROM \"public\".\"members\" perm_c0_a0"
                + " WHERE perm_c0_a0.\"org_id\" = n.\"id\" AND EXISTS (SELECT 1 FROM \"public\".\"notes\" perm_c0_a1"
                + " WHERE perm_c0_a1.\"org_id\" = perm_c0_a0.\"org_id\" AND perm_c0_a1.\"owner_id\" = perm_c0_a0.\"user_id\""
                + " AND TRUE))");
    }

    @Test
    void exists_isUncorrelated() {
        SqlFragment fragment = compile(
                "{\"_exists\": {\"_table\": \"public.members\", \"_where\": {\"role\": {\"_eq\": \"admin\"}}}}", NOTES);

        assertThat(fragment.sql()).isEqualTo(
                "EXISTS (SELECT 1 FROM \"public\".\"members\" perm_c0_a0 WHERE perm_c0_a0.\"role\" = :perm_c0_p0)");
    }

    @Test
    void oneNamer_keepsNamesUniqueAcrossCompilations() {
        ParamNamer namer = new ParamNamer("perm");
        SqlFragment first = compile(parse("{\"publicOrgId\": {\"id\": {\"_eq\": 1}}}"), NOTES, namer);
        SqlFragment second = compile(parse("{\"publicOrgId\": {\"id\": {\"_eq\": 1}}}"), NOTES, namer);

        assertThat(first.params()).containsOnlyKeys("perm_p0");
        assertThat(second.params()).containsOnlyKeys("perm_p1");
        assertThat(second.sql()).contains("perm_a1").doesNotContain("perm_a0");
    }

    @Test
    void quotedOuterAlias_isUsedAsGiven() {
        SqlFragment fragment = FilterCompiler.compile(parse("{\"id\": {\"_eq\": 1}}"), NOTES, "\"a1b2c3d4\"",
                SCHEMA, BINDING, new ParamNamer("perm"));

        assertThat(fragment.sql()).isEqualTo("\"a1b2c3d4\".\"id\" = :perm_p0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"n; DROP", "\"a\"\"b\"", "1n", ""})
    void unsafeOuterAlias_isRefused(String alias) {
        assertThatThrownBy(() -> FilterCompiler.compile(BoolExp.TRUE, NOTES, alias, SCHEMA, BINDING,
                new ParamNamer("perm"))).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"perm-1", "Perm", "", "1perm"})
    void unsafeNamerPrefix_isRefused(String prefix) {
        assertThatThrownBy(() -> new ParamNamer(prefix)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownColumn_isRefused() {
        assertCode(() -> compile("{\"nope\": {\"_eq\": 1}}", NOTES), "unknown_column");
    }

    @Test
    void unknownColumnInsideARelationship_isRefused() {
        assertCode(() -> compile("{\"publicOrgId\": {\"title\": {\"_eq\": \"x\"}}}", NOTES), "unknown_column");
    }

    @Test
    void unknownRelationship_isRefused() {
        assertCode(() -> compile("{\"publicOrgs\": {}}", NOTES), "unknown_relationship");
    }

    @Test
    void unknownTable_isRefused() {
        assertCode(() -> compile("{}", "public.nope"), "unknown_table");
        assertCode(() -> compile("{\"_exists\": {\"_table\": \"public.nope\", \"_where\": {}}}", NOTES),
                "unknown_table");
    }

    @Test
    void likeOnANonTextColumn_isRefused() {
        assertCode(() -> compile("{\"org_id\": {\"_like\": \"1%\"}}", NOTES), "unsupported_operator");
    }

    @Test
    void missingVariable_isRefused() {
        assertCode(() -> compile("{\"org_id\": {\"_eq\": \"X-Excalibase-Org-Id\"}}", NOTES),
                "missing_session_variable");
    }

    @Test
    void missingVariableInsideARelationship_isRefused() {
        assertCode(() -> compile("{\"publicMembers\": {\"role\": {\"_eq\": \"X-Excalibase-Team\"}}}", NOTES),
                "missing_session_variable");
    }

    @Test
    void membersTable_isReachableThroughItsOwnForwardRelationship() {
        assertThat(compile("{\"publicOrgId\": {}}", MEMBERS).sql()).startsWith("EXISTS (SELECT 1 FROM \"public\".\"orgs\"");
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo(code);
    }
}
