package io.github.excalibase.permissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.SessionCompare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXC-680: a permission may compare a session variable with a fixed value, e.g. require aal2. */
class SessionCompareParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BoolExp parse(String json) {
        try {
            return BoolExpParser.parse(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void aVariableKeyComparesTheVariable() {
        assertThat(parse("{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}"))
                .isEqualTo(new SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.EQ,
                        new Literal("aal2")));
    }

    @Test
    void everyOperatorItTakes() {
        assertThat(parse("{\"x-excalibase-aal\": {\"_neq\": \"aal1\", \"_in\": [\"aal2\", \"aal3\"],"
                + " \"_nin\": [], \"_is_null\": false}}"))
                .isEqualTo(new And(List.of(
                        new SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.NEQ, new Literal("aal1")),
                        new SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.IN,
                                new Literal(List.of("aal2", "aal3"))),
                        new SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.NIN, new Literal(List.of())),
                        new SessionCompare(new SessionVariable("x-excalibase-aal"), ComparisonOperator.IS_NULL, new Literal(false)))));
    }

    @Test
    void itNestsUnderLogicAndBesideColumns() {
        BoolExp parsed = parse("{\"_or\": [{\"X-Excalibase-Aal\": {\"_eq\": \"aal2\"}}, {\"public\": {\"_eq\": true}}]}");
        assertThat(parsed).isInstanceOf(BoolExp.Or.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"X-Excalibase-Aal\": {\"_gt\": \"aal1\"}}",
            "{\"X-Excalibase-Aal\": {\"_like\": \"aal%\"}}",
            "{\"X-Excalibase-Aal\": {\"_eq\": 2}}",
            "{\"X-Excalibase-Aal\": {\"_eq\": true}}",
            "{\"X-Excalibase-Aal\": {\"_eq\": null}}",
            "{\"X-Excalibase-Aal\": {\"_eq\": \"X-Excalibase-User-Id\"}}",
            "{\"X-Excalibase-Aal\": {\"_in\": [\"aal2\", 3]}}",
            "{\"X-Excalibase-Aal\": {\"_in\": \"X-Excalibase-Levels\"}}",
            "{\"X-Excalibase-Aal\": {\"_is_null\": \"yes\"}}",
            "{\"X-Excalibase-Aal\": {}}",
            "{\"X-Excalibase-Aal\": {\"level\": {\"_eq\": \"aal2\"}}}",
            "{\"X-Excalibase-\": {\"_eq\": \"aal2\"}}",
            "{\"X-Excalibase-A al\": {\"_eq\": \"aal2\"}}",
    })
    void whatItDoesNotTake_isRefused(String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(PermissionDocumentException.class);
    }
}
