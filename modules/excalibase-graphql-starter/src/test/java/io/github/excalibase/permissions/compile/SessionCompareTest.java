package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.compile.RowPredicate.Outcome;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.U1;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static io.github.excalibase.permissions.compile.PermissionDialect.MYSQL;
import static io.github.excalibase.permissions.compile.PermissionDialect.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXC-680: a session variable compared with a fixed value is decided for the request, before any row:
 * it compiles to TRUE or FALSE in either dialect, and the in-memory predicate agrees.
 */
class SessionCompareTest {

    private static final SchemaInfo PG = FixtureSchema.schema();
    private static final SchemaInfo MY = MysqlFixtureSchema.schema();

    private static SessionBinding session(String aal) {
        Map<String, String> variables = new HashMap<>(Map.of("x-excalibase-user-id", U1));
        if (aal != null) {
            variables.put("X-Excalibase-Aal", aal);
        }
        return new SessionBinding(variables);
    }

    private static String postgres(String json, SessionBinding binding) {
        return FilterCompiler.compile(POSTGRES, parse(json), NOTES, "n", PG, binding, new ParamNamer("perm")).sql();
    }

    private static String mysql(String json, SessionBinding binding) {
        return FilterCompiler.compile(MYSQL, parse(json), NOTES, "n", MY, binding, new ParamNamer("perm")).sql();
    }

    private static Outcome inMemory(String json, SessionBinding binding) {
        return RowPredicate.compile(POSTGRES, parse(json), NOTES, PG, binding).test(Map.of());
    }

    @ParameterizedTest(name = "{0} with aal={1}")
    @CsvSource(nullValues = "none", value = {
            "'{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}', aal2, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}', aal1, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}', AAL2, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_neq\": \"aal1\"}}', aal2, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_neq\": \"aal1\"}}', aal1, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_in\": [\"aal2\", \"aal3\"]}}', aal3, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_in\": [\"aal2\", \"aal3\"]}}', aal1, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_in\": []}}', aal2, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_nin\": [\"aal1\"]}}', aal2, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_nin\": [\"aal1\"]}}', aal1, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_is_null\": true}}', none, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_is_null\": true}}', aal1, FALSE",
            "'{\"X-Excalibase-Aal\": {\"_is_null\": false}}', aal1, TRUE",
            "'{\"X-Excalibase-Aal\": {\"_is_null\": false}}', none, FALSE",
    })
    void decidedForTheRequest(String json, String aal, String expected) {
        SessionBinding binding = session(aal);
        assertThat(postgres(json, binding)).isEqualTo(expected);
        assertThat(mysql(json, binding)).isEqualTo(expected);
        assertThat(inMemory(json, binding)).isEqualTo(expected.equals("TRUE") ? Outcome.MATCH : Outcome.NO_MATCH);
    }

    @Test
    void underNot_theDecidedValueIsNegatedLikeAnyOther() {
        String json = "{\"_not\": {\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}}";
        assertThat(postgres(json, session("aal2"))).isEqualTo("(NOT TRUE)");
        assertThat(inMemory(json, session("aal2"))).isEqualTo(Outcome.NO_MATCH);
        assertThat(inMemory(json, session("aal1"))).isEqualTo(Outcome.MATCH);
    }

    @Test
    void besideAColumnCheck_bothMustHold() {
        String json = "{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}, \"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}";
        assertThat(postgres(json, session("aal2"))).startsWith("(TRUE AND n.\"owner_id\" = ");
        assertThat(postgres(json, session("aal1"))).startsWith("(FALSE AND ");
    }

    @Test
    void aMissingVariable_failsTheRequestUnlessTheCheckAsksWhetherItIsThere() {
        assertThatThrownBy(() -> postgres("{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}", session(null)))
                .isInstanceOf(PermissionEvaluationException.class)
                .extracting("code").isEqualTo(PermissionEvaluationException.MISSING_SESSION_VARIABLE);
        assertThatThrownBy(() -> inMemory("{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}", session(null)))
                .isInstanceOf(PermissionEvaluationException.class);
    }

    @Test
    void theSchemaCheckLeavesTheVariableForTheRequest() {
        ExpressionCheck.check(POSTGRES, parse("{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}"), NOTES, PG);
        assertThat(TablesRead.of(parse("{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}"), NOTES, PG)).isEmpty();
    }
}
