package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.excalibase.permissions.compile.FixtureSchema.NOTES;
import static io.github.excalibase.permissions.compile.FixtureSchema.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionCheckTest {

    private static final SchemaInfo SCHEMA = FixtureSchema.schema();

    private static void check(String json) {
        ExpressionCheck.check(parse(json), NOTES, SCHEMA);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"owner_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}",
            "{\"id\": {\"_in\": [1, 2]}, \"title\": {\"_like\": \"a%\"}}",
            "{\"_or\": [{\"status\": {\"_is_null\": true}}, {\"_not\": {\"priority\": {\"_gt\": 3}}}]}",
            "{\"publicOrgId\": {\"name\": {\"_eq\": \"acme\"}}}",
            "{\"_exists\": {\"_table\": \"public.members\", \"_where\": {\"user_id\": {\"_eq\": \"X-Excalibase-User-Id\"}}}}"
    })
    void acceptsExpressionsThatFitTheSchema_withoutAnySessionVariable(String json) {
        assertThatCode(() -> check(json)).doesNotThrowAnyException();
    }

    @Test
    void unknownColumn_isRefused() {
        assertThatThrownBy(() -> check("{\"nope\": {\"_eq\": 1}}"))
                .isInstanceOfSatisfying(PermissionEvaluationException.class,
                        e -> assertThat(e.code()).isEqualTo(PermissionEvaluationException.UNKNOWN_COLUMN));
    }

    @Test
    void unknownColumnInsideARelationship_isRefused() {
        assertThatThrownBy(() -> check("{\"publicOrgId\": {\"nope\": {\"_eq\": 1}}}"))
                .isInstanceOf(PermissionEvaluationException.class);
    }

    @Test
    void unknownRelationship_isRefused() {
        assertThatThrownBy(() -> check("{\"nowhere\": {\"id\": {\"_eq\": 1}}}"))
                .isInstanceOf(PermissionEvaluationException.class);
    }

    @Test
    void unknownExistsTable_isRefused() {
        assertThatThrownBy(() -> check("{\"_exists\": {\"_table\": \"public.ghost\", \"_where\": {}}}"))
                .isInstanceOf(PermissionEvaluationException.class);
    }

    @Test
    void literalOfTheWrongType_isRefused() {
        assertThatThrownBy(() -> check("{\"id\": {\"_eq\": \"not-a-number\"}}"))
                .isInstanceOf(PermissionEvaluationException.class);
    }

    @Test
    void likeOnANonTextColumn_isRefused() {
        assertThatThrownBy(() -> check("{\"id\": {\"_like\": \"1%\"}}"))
                .isInstanceOf(PermissionEvaluationException.class);
    }
}
