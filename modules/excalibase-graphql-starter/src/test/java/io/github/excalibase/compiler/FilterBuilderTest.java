package io.github.excalibase.compiler;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FilterBuilderTest {

    @Mock
    SqlDialect dialect;

    private FilterBuilder filterBuilder;

    @BeforeEach
    void setUp() {
        lenient().when(dialect.quoteIdentifier(anyString())).thenAnswer(inv -> "\"" + inv.getArgument(0) + "\"");
        lenient().when(dialect.ilike(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " ILIKE " + inv.getArgument(1));
        lenient().when(dialect.orderByNulls(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " " + inv.getArgument(1) + " NULLS " + inv.getArgument(2));
        filterBuilder = new FilterBuilder(dialect, 100);
    }

    private Field parseField(String query) {
        Document doc = Parser.parse(query);
        OperationDefinition op = doc.getDefinitionsOfType(OperationDefinition.class).getFirst();
        for (Selection<?> schema : op.getSelectionSet().getSelections()) {
            if (schema instanceof Field f) return f;
        }
        throw new IllegalStateException("no field");
    }

    @Nested
    @DisplayName("constructor overloads")
    class Constructors {
        @Test
        @DisplayName("two-arg constructor sets dialect and maxRows without schema info")
        void twoArg_build_worksForSimpleEq() {
            FilterBuilder fb = new FilterBuilder(dialect, 50);
            Field field = parseField("{ users(where: { id: { eq: 1 } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            fb.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains("\"id\" = :p_id_eq");
            assertThat(params).hasSize(1);
        }

        @Test
        @DisplayName("four-arg constructor accepts schema info for contains-on-jsonb dispatch")
        void fourArg_build_doesNotThrow() {
            FilterBuilder fb = new FilterBuilder(dialect, 50, null, "public");
            Field field = parseField("{ users(where: { id: { eq: 1 } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            fb.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains("\"id\" = :p_id_eq");
        }
    }

    @Nested
    @DisplayName("full-text search operators")
    class FtsOperators {
        @Test
        @DisplayName("webSearch invokes dialect with WEB_SEARCH variant and adds SQL + param")
        void webSearch_appendsClauseAndParam() {
            when(dialect.fullTextSearchSql(anyString(), anyString(), eq(SqlDialect.FtsVariant.WEB_SEARCH)))
                    .thenReturn(Optional.of("ws_clause"));
            Field field = parseField("{ docs(where: { body: { webSearch: \"foo OR bar\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("ws_clause");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_body_websearch"));
            assertThat(params).containsValue("foo OR bar");
        }

        @Test
        @DisplayName("phraseSearch invokes dialect with PHRASE variant")
        void phraseSearch_appendsClause() {
            when(dialect.fullTextSearchSql(anyString(), anyString(), eq(SqlDialect.FtsVariant.PHRASE)))
                    .thenReturn(Optional.of("ph_clause"));
            Field field = parseField("{ docs(where: { body: { phraseSearch: \"exact phrase\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("ph_clause");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_body_phrase"));
        }

        @Test
        @DisplayName("rawSearch invokes dialect with RAW variant")
        void rawSearch_appendsClause() {
            when(dialect.fullTextSearchSql(anyString(), anyString(), eq(SqlDialect.FtsVariant.RAW)))
                    .thenReturn(Optional.of("raw_clause"));
            Field field = parseField("{ docs(where: { body: { rawSearch: \"term1 & term2\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("raw_clause");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_body_rawts"));
        }

        @Test
        @DisplayName("FTS operator is silently dropped when dialect returns empty Optional")
        void fts_dialectReturnsEmpty_dropsCondition() {
            when(dialect.fullTextSearchSql(anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());
            Field field = parseField("{ docs(where: { body: { webSearch: \"foo\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).isEmpty();
            assertThat(params).isEmpty();
        }
    }

    @Nested
    @DisplayName("regex operators")
    class RegexOperators {
        @Test
        @DisplayName("regex calls dialect with caseInsensitive=false")
        void regex_caseSensitive() {
            when(dialect.regexSql(anyString(), anyString(), eq(false)))
                    .thenReturn(Optional.of("re_clause"));
            Field field = parseField("{ users(where: { name: { regex: \"^A.*\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("re_clause");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_name_regex"));
        }

        @Test
        @DisplayName("iregex calls dialect with caseInsensitive=true")
        void iregex_caseInsensitive() {
            when(dialect.regexSql(anyString(), anyString(), eq(true)))
                    .thenReturn(Optional.of("ire_clause"));
            Field field = parseField("{ users(where: { name: { iregex: \"foo\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("ire_clause");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_name_iregex"));
        }

        @Test
        @DisplayName("regex is dropped when dialect does not support it")
        void regex_dialectReturnsEmpty_drops() {
            when(dialect.regexSql(anyString(), anyString(), anyBoolean()))
                    .thenReturn(Optional.empty());
            Field field = parseField("{ users(where: { name: { regex: \"foo\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).isEmpty();
        }
    }

    @Nested
    @DisplayName("null predicates")
    class NullPredicates {
        @org.junit.jupiter.params.ParameterizedTest(name = "{0} produces {1}")
        @org.junit.jupiter.params.provider.CsvSource({
                "{ isNull: true },      IS NULL",
                "{ isNull: false },     IS NOT NULL",
                "{ isNotNull: true },   IS NOT NULL",
                "{ isNotNull: false },  IS NULL"
        })
        @DisplayName("isNull/isNotNull with true/false produce the expected SQL fragment")
        void nullPredicate_matrix(String predicate, String expectedSuffix) {
            Field field = parseField("{ users(where: { deleted_at: " + predicate + " }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().endsWith(" " + expectedSuffix);
        }
    }

    @Nested
    @DisplayName("notIn and nin aliases")
    class NotIn {
        @Test
        @DisplayName("notIn builds NOT IN list")
        void notIn_expandsToNotInList() {
            Field field = parseField("{ users(where: { id: { notIn: [1, 2, 3] } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains("NOT IN");
            assertThat(params).hasSize(3);
        }

        @Test
        @DisplayName("nin alias produces the same NOT IN clause")
        void nin_aliasOfNotIn() {
            Field field = parseField("{ users(where: { id: { nin: [1, 2] } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains("NOT IN");
            assertThat(params).hasSize(2);
        }
    }

    @Nested
    @DisplayName("default branch (unknown operator)")
    class DefaultBranch {
        @Test
        @DisplayName("unknown operator falls through to equality")
        void unknownOp_fallsBackToEq() {
            Field field = parseField("{ users(where: { status: { someCustom: \"x\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains(" = :");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_status_someCustom"));
        }
    }

    @Nested
    @DisplayName("applyWhere overloads")
    class ApplyWhere {
        @Test
        @DisplayName("applyWhere without tableName appends the WHERE clause")
        void applyWhereNoTable_appendsClause() {
            Field field = parseField("{ users(where: { id: { eq: 1 } }) { id } }");
            StringBuilder sql = new StringBuilder("SELECT * FROM users t");
            Map<String, Object> params = new HashMap<>();

            filterBuilder.applyWhere(sql, field, "t", params);

            assertThat(sql.toString()).contains(" WHERE ").contains("\"id\" = :p_id_eq");
        }

        @Test
        @DisplayName("applyWhere does nothing when field has no where argument")
        void applyWhereNoArg_doesNotAppend() {
            Field field = parseField("{ users { id } }");
            StringBuilder sql = new StringBuilder("SELECT * FROM users t");
            Map<String, Object> params = new HashMap<>();

            filterBuilder.applyWhere(sql, field, "t", params);

            assertThat(sql.toString()).doesNotContain("WHERE");
        }
    }

    @Nested
    @DisplayName("order by direction parsing")
    class OrderBy {
        @Test
        @DisplayName("AscNullsFirst/AscNullsLast/DescNullsFirst/DescNullsLast route through orderByNulls")
        void nullsOrderVariants_routeThroughDialect() {
            Field field = parseField(
                    "{ users(orderBy: { a: AscNullsFirst, b: AscNullsLast, c: DescNullsFirst, d: DescNullsLast }) { id } }");
            StringBuilder sql = new StringBuilder("SELECT * FROM users t");

            filterBuilder.applyOrderBy(sql, field, "t");

            String out = sql.toString();
            assertThat(out)
                    .contains("ASC NULLS FIRST")
                    .contains("ASC NULLS LAST")
                    .contains("DESC NULLS FIRST")
                    .contains("DESC NULLS LAST");
        }

        @Test
        @DisplayName("unknown direction throws IllegalArgumentException")
        void unknownDirection_throws() {
            Field field = parseField("{ users(orderBy: { a: Nonsense }) { id } }");
            StringBuilder sql = new StringBuilder("SELECT * FROM users t");

            assertThatThrownBy(() -> filterBuilder.applyOrderBy(sql, field, "t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ORDER BY");
        }

        @Test
        @DisplayName("parseOrderBy returns uppercased direction pairs")
        void parseOrderBy_returnsUppercasedDirections() {
            Field field = parseField("{ users(orderBy: { name: ASC, id: DESC }) { id } }");

            List<String[]> pairs = filterBuilder.parseOrderBy(field);

            assertThat(pairs).hasSize(2);
            assertThat(pairs.get(0)[0]).isEqualTo("name");
            assertThat(pairs.get(0)[1]).isEqualTo("ASC");
            assertThat(pairs.get(1)[0]).isEqualTo("id");
            assertThat(pairs.get(1)[1]).isEqualTo("DESC");
        }

        @Test
        @DisplayName("parseOrderBy returns empty list when no orderBy argument")
        void parseOrderBy_noArg_returnsEmpty() {
            Field field = parseField("{ users { id } }");

            List<String[]> pairs = filterBuilder.parseOrderBy(field);

            assertThat(pairs).isEmpty();
        }
    }

    @Nested
    @DisplayName("contains operator dispatches on column type")
    class ContainsDispatch {
        @Test
        @DisplayName("contains on jsonb column invokes dialect.jsonPredicateSql CONTAINS")
        void contains_onJsonbColumn_usesContainmentOperator() {
            SchemaInfo schema = new SchemaInfo();
            schema.addColumn("docs", "meta", "jsonb");
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.CONTAINS), anyString(), anyString()))
                    .thenReturn(Optional.of("jsonb_contains"));
            FilterBuilder fb = new FilterBuilder(dialect, 100, schema, "public");
            Field field = parseField("{ docs(where: { meta: { contains: \"x\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            fb.buildWhereConditions(field, "t", params, conditions, "docs");

            assertThat(conditions).containsExactly("jsonb_contains");
            assertThat(params.keySet()).anyMatch(k -> k.startsWith("p_meta_jc"));
        }

        @Test
        @DisplayName("contains on json column uses jsonPredicateSql too")
        void contains_onJsonColumn_alsoUsesJsonPredicate() {
            SchemaInfo schema = new SchemaInfo();
            schema.addColumn("docs", "meta", "json");
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.CONTAINS), anyString(), anyString()))
                    .thenReturn(Optional.of("json_contains"));
            FilterBuilder fb = new FilterBuilder(dialect, 100, schema, "public");
            Field field = parseField("{ docs(where: { meta: { contains: \"x\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            fb.buildWhereConditions(field, "t", params, conditions, "docs");

            assertThat(conditions).containsExactly("json_contains");
        }

        @Test
        @DisplayName("contains on text column falls back to LIKE %pat% with sourrounding wildcards")
        void contains_onTextColumn_fallsBackToLike() {
            SchemaInfo schema = new SchemaInfo();
            schema.addColumn("users", "name", "text");
            FilterBuilder fb = new FilterBuilder(dialect, 100, schema, "public");
            Field field = parseField("{ users(where: { name: { contains: \"abc\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            fb.buildWhereConditions(field, "t", params, conditions, "users");

            assertThat(conditions).singleElement().asString().contains(" LIKE :");
            assertThat(params).containsValue("%abc%");
        }

        @Test
        @DisplayName("contains without schemaInfo treats column as text by default")
        void contains_withoutSchemaInfo_usesLike() {
            Field field = parseField("{ users(where: { name: { contains: \"foo\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains(" LIKE :");
            assertThat(params).containsValue("%foo%");
        }
    }

    @Nested
    @DisplayName("jsonb key predicates")
    class JsonKeyPredicates {
        @Test
        @DisplayName("hasKey invokes dialect with HAS_KEY variant")
        void hasKey_callsDialect() {
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.HAS_KEY), anyString(), anyString()))
                    .thenReturn(Optional.of("has_key"));
            Field field = parseField("{ docs(where: { meta: { hasKey: \"foo\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("has_key");
        }

        @Test
        @DisplayName("hasKeys binds each array element and invokes dialect with HAS_ALL_KEYS")
        void hasKeys_expandsArrayAndCallsDialect() {
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.HAS_ALL_KEYS), anyString(), anyString()))
                    .thenReturn(Optional.of("has_all_keys"));
            Field field = parseField("{ docs(where: { meta: { hasKeys: [\"a\", \"b\"] } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("has_all_keys");
            assertThat(params).hasSize(2);
        }

        @Test
        @DisplayName("hasAnyKeys uses HAS_ANY_KEYS variant")
        void hasAnyKeys_usesHasAnyKeysVariant() {
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.HAS_ANY_KEYS), anyString(), anyString()))
                    .thenReturn(Optional.of("has_any"));
            Field field = parseField("{ docs(where: { meta: { hasAnyKeys: [\"a\"] } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("has_any");
        }

        @Test
        @DisplayName("containedBy uses CONTAINED_BY variant")
        void containedBy_callsDialect() {
            when(dialect.jsonPredicateSql(eq(SqlDialect.JsonPredicate.CONTAINED_BY), anyString(), anyString()))
                    .thenReturn(Optional.of("contained_by"));
            Field field = parseField("{ docs(where: { meta: { containedBy: \"x\" } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).containsExactly("contained_by");
        }
    }

    @Nested
    @DisplayName("filter alias accepted alongside where")
    class FilterAlias {
        @Test
        @DisplayName("field with argument name 'filter' is also recognized")
        void filterArg_equivalentToWhere() {
            Field field = parseField("{ users(filter: { id: { eq: 42 } }) { id } }");
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>();

            filterBuilder.buildWhereConditions(field, "t", params, conditions);

            assertThat(conditions).singleElement().asString().contains("\"id\" = :p_id_eq");
        }
    }
}
