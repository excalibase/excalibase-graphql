package io.github.excalibase.permissions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.permissions.BoolExp.And;
import io.github.excalibase.permissions.BoolExp.ColumnCompare;
import io.github.excalibase.permissions.BoolExp.Exists;
import io.github.excalibase.permissions.BoolExp.Not;
import io.github.excalibase.permissions.BoolExp.Or;
import io.github.excalibase.permissions.BoolExp.Relationship;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The boolean-expression grammar of docs/features/permissions.md §4, refused whole when anything is off. */
class BoolExpParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BoolExp parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return BoolExpParser.parse(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ColumnCompare compare(String column, ComparisonOperator op, Operand operand) {
        return new ColumnCompare(column, op, operand);
    }

    @Test
    void emptyObject_isTrue() {
        assertThat(parse("{}")).isEqualTo(BoolExp.TRUE);
    }

    static Stream<Arguments> everyScalarOperator() {
        return Stream.of(
                Arguments.of("_eq", ComparisonOperator.EQ),
                Arguments.of("_neq", ComparisonOperator.NEQ),
                Arguments.of("_gt", ComparisonOperator.GT),
                Arguments.of("_gte", ComparisonOperator.GTE),
                Arguments.of("_lt", ComparisonOperator.LT),
                Arguments.of("_lte", ComparisonOperator.LTE),
                Arguments.of("_like", ComparisonOperator.LIKE),
                Arguments.of("_nlike", ComparisonOperator.NLIKE));
    }

    @ParameterizedTest
    @MethodSource("everyScalarOperator")
    void scalarOperator_withStringLiteral(String jsonOp, ComparisonOperator op) {
        assertThat(parse("{\"status\": {\"" + jsonOp + "\": \"paid\"}}"))
                .isEqualTo(compare("status", op, new Literal("paid")));
    }

    @ParameterizedTest
    @MethodSource("everyScalarOperator")
    void scalarOperator_withSessionVariable(String jsonOp, ComparisonOperator op) {
        assertThat(parse("{\"owner_id\": {\"" + jsonOp + "\": \"X-Excalibase-User-Id\"}}"))
                .isEqualTo(compare("owner_id", op, new SessionVariable("x-excalibase-user-id")));
    }

    @Test
    void comparison_withNumbersAndBooleans_keepsPlainJavaValues() {
        assertThat(parse("{\"total\": {\"_gt\": 10}}"))
                .isEqualTo(compare("total", ComparisonOperator.GT, new Literal(10L)));
        assertThat(parse("{\"price\": {\"_lte\": 9.95}}"))
                .isEqualTo(compare("price", ComparisonOperator.LTE, new Literal(new BigDecimal("9.95"))));
        assertThat(parse("{\"active\": {\"_eq\": true}}"))
                .isEqualTo(compare("active", ComparisonOperator.EQ, new Literal(true)));
    }

    @Test
    void comparison_withAHugeInteger_keepsItExact() {
        assertThat(parse("{\"n\": {\"_eq\": 123456789012345678901234567890}}"))
                .isEqualTo(compare("n", ComparisonOperator.EQ,
                        new Literal(new BigDecimal("123456789012345678901234567890"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_in", "_nin"})
    void inOperators_takeAnArrayOfScalars(String jsonOp) {
        ComparisonOperator op = "_in".equals(jsonOp) ? ComparisonOperator.IN : ComparisonOperator.NIN;
        assertThat(parse("{\"status\": {\"" + jsonOp + "\": [\"a\", 2, false]}}"))
                .isEqualTo(compare("status", op, new Literal(List.of("a", 2L, false))));
    }

    @Test
    void inOperator_takesASessionVariableHoldingAnArray() {
        assertThat(parse("{\"org_id\": {\"_in\": \"x-excalibase-org-ids\"}}"))
                .isEqualTo(compare("org_id", ComparisonOperator.IN, new SessionVariable("x-excalibase-org-ids")));
    }

    @Test
    void inOperator_acceptsAnEmptyArray() {
        assertThat(parse("{\"status\": {\"_in\": []}}"))
                .isEqualTo(compare("status", ComparisonOperator.IN, new Literal(List.of())));
    }

    @Test
    void isNull_takesABoolean() {
        assertThat(parse("{\"deleted_at\": {\"_is_null\": true}}"))
                .isEqualTo(compare("deleted_at", ComparisonOperator.IS_NULL, new Literal(true)));
        assertThat(parse("{\"deleted_at\": {\"_is_null\": false}}"))
                .isEqualTo(compare("deleted_at", ComparisonOperator.IS_NULL, new Literal(false)));
    }

    @Test
    void severalOperatorsOnOneColumn_areAnded() {
        assertThat(parse("{\"total\": {\"_gt\": 1, \"_lt\": 5}}")).isEqualTo(new And(List.of(
                compare("total", ComparisonOperator.GT, new Literal(1L)),
                compare("total", ComparisonOperator.LT, new Literal(5L)))));
    }

    @Test
    void severalKeysInOneObject_areAnded() {
        assertThat(parse("{\"a\": {\"_eq\": 1}, \"b\": {\"_eq\": 2}}")).isEqualTo(new And(List.of(
                compare("a", ComparisonOperator.EQ, new Literal(1L)),
                compare("b", ComparisonOperator.EQ, new Literal(2L)))));
    }

    @Test
    void logicalOperators_nest() {
        BoolExp parsed = parse("""
                {"_or": [
                  {"_and": [{"a": {"_eq": 1}}, {"_not": {"b": {"_eq": 2}}}]},
                  {"c": {"_is_null": true}}
                ]}
                """);

        assertThat(parsed).isEqualTo(new Or(List.of(
                new And(List.of(
                        compare("a", ComparisonOperator.EQ, new Literal(1L)),
                        new Not(compare("b", ComparisonOperator.EQ, new Literal(2L))))),
                compare("c", ComparisonOperator.IS_NULL, new Literal(true)))));
    }

    @Test
    void emptyAndOr_areKeptAsWritten() {
        assertThat(parse("{\"_and\": []}")).isEqualTo(new And(List.of()));
        assertThat(parse("{\"_or\": []}")).isEqualTo(new Or(List.of()));
    }

    @Test
    void relationship_wrapsTheRelatedTablesExpression() {
        assertThat(parse("{\"orderItems\": {\"product_id\": {\"_eq\": 7}}}")).isEqualTo(new Relationship(
                "orderItems", compare("product_id", ComparisonOperator.EQ, new Literal(7L))));
    }

    @Test
    void relationship_withEmptyExpression_meansARelatedRowExists() {
        assertThat(parse("{\"customer\": {}}")).isEqualTo(new Relationship("customer", BoolExp.TRUE));
    }

    @Test
    void exists_namesATableAndAWhere() {
        assertThat(parse("""
                {"_exists": {"_table": "public.members", "_where": {"user_id": {"_eq": "X-Excalibase-User-Id"}}}}
                """)).isEqualTo(new Exists("public.members",
                compare("user_id", ComparisonOperator.EQ, new SessionVariable("x-excalibase-user-id"))));
    }

    @Test
    void sessionVariable_isMatchedCaseInsensitivelyAndLowerCased() {
        assertThat(parse("{\"a\": {\"_eq\": \"X-EXCALIBASE-TENANT\"}}"))
                .isEqualTo(compare("a", ComparisonOperator.EQ, new SessionVariable("x-excalibase-tenant")));
    }

    @Test
    void sessionVariable_mayNameAClaimWithUnderscores() {
        assertThat(parse("{\"a\": {\"_eq\": \"X-Excalibase-tenant_id\"}}"))
                .isEqualTo(compare("a", ComparisonOperator.EQ, new SessionVariable("x-excalibase-tenant_id")));
    }

    @Test
    void depthSixteen_isAccepted_seventeen_isRefused() {
        assertThat(parse(nestedNots(15))).isNotNull();
        assertThatThrownBy(() -> parse(nestedNots(16)))
                .isInstanceOf(PermissionDocumentException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void twoHundredNodes_areAccepted_moreAreRefused() {
        assertThat(parse(orOfComparisons(199))).isNotNull();
        assertThatThrownBy(() -> parse(orOfComparisons(200)))
                .isInstanceOf(PermissionDocumentException.class)
                .hasMessageContaining("nodes");
    }

    /** {@code count} nested {@code _not}s around a leaf: depth count + 1. */
    private static String nestedNots(int count) {
        return "{\"_not\": ".repeat(count) + "{\"a\": {\"_eq\": 1}}" + "}".repeat(count);
    }

    /** One {@code _or} holding {@code count} comparisons: count + 1 nodes. */
    private static String orOfComparisons(int count) {
        return "{\"_or\": [" + String.join(",", Collections.nCopies(count, "{\"a\": {\"_eq\": 1}}")) + "]}";
    }

    static Stream<Arguments> invalidExpressions() {
        return Stream.of(
                Arguments.of("not an object", "[]"),
                Arguments.of("scalar root", "1"),
                Arguments.of("null root", "null"),
                Arguments.of("unknown operator", "{\"a\": {\"_ilike\": \"x\"}}"),
                Arguments.of("unknown logical key", "{\"_xor\": []}"),
                Arguments.of("column value not an object", "{\"a\": 1}"),
                Arguments.of("operator mixed with a field", "{\"a\": {\"_eq\": 1, \"b\": {\"_eq\": 2}}}"),
                Arguments.of("_and not an array", "{\"_and\": {}}"),
                Arguments.of("_or not an array", "{\"_or\": {\"a\": {\"_eq\": 1}}}"),
                Arguments.of("_and element not an object", "{\"_and\": [1]}"),
                Arguments.of("_not not an object", "{\"_not\": []}"),
                Arguments.of("null comparison value", "{\"a\": {\"_eq\": null}}"),
                Arguments.of("object comparison value", "{\"a\": {\"_eq\": {\"x\": 1}}}"),
                Arguments.of("array for _eq", "{\"a\": {\"_eq\": [1]}}"),
                Arguments.of("number for _like", "{\"a\": {\"_like\": 5}}"),
                Arguments.of("scalar for _in", "{\"a\": {\"_in\": 5}}"),
                Arguments.of("plain string for _in", "{\"a\": {\"_in\": \"a,b\"}}"),
                Arguments.of("null inside _in", "{\"a\": {\"_in\": [1, null]}}"),
                Arguments.of("nested array inside _in", "{\"a\": {\"_in\": [[1]]}}"),
                Arguments.of("session variable inside _in", "{\"a\": {\"_in\": [\"x-excalibase-user-id\"]}}"),
                Arguments.of("string for _is_null", "{\"a\": {\"_is_null\": \"true\"}}"),
                Arguments.of("session variable for _is_null", "{\"a\": {\"_is_null\": \"x-excalibase-flag\"}}"),
                Arguments.of("empty variable suffix", "{\"a\": {\"_eq\": \"X-Excalibase-\"}}"),
                Arguments.of("space in variable", "{\"a\": {\"_eq\": \"X-Excalibase-User Id\"}}"),
                Arguments.of("bad column name", "{\"a-b\": {\"_eq\": 1}}"),
                Arguments.of("_exists without _table", "{\"_exists\": {\"_where\": {}}}"),
                Arguments.of("_exists without _where", "{\"_exists\": {\"_table\": \"public.t\"}}"),
                Arguments.of("_exists unqualified table", "{\"_exists\": {\"_table\": \"t\", \"_where\": {}}}"),
                Arguments.of("_exists upper-case table", "{\"_exists\": {\"_table\": \"public.T\", \"_where\": {}}}"),
                Arguments.of("_exists extra key", "{\"_exists\": {\"_table\": \"public.t\", \"_where\": {}, \"x\": 1}}"),
                Arguments.of("_exists not an object", "{\"_exists\": \"public.t\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidExpressions")
    void invalidExpression_isRefused(String reason, String json) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(PermissionDocumentException.class);
    }

    @Test
    void missingExpression_isRefusedNeverReadAsTrue() {
        assertThatThrownBy(() -> BoolExpParser.parse(null)).isInstanceOf(PermissionDocumentException.class);
    }
}
