package io.github.excalibase.compiler;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import io.github.excalibase.FilterCapabilities;
import io.github.excalibase.FilterColumnKind;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A filter the engine cannot run is an error, never a condition left out: a dropped condition answers more rows
 * than the caller asked for.
 */
class FilterBuilderFailLoudTest {

    private static final String TABLE = "docs";

    private static SchemaInfo schema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn(TABLE, "id", "bigint");
        info.addColumn(TABLE, "title", "text");
        info.addColumn(TABLE, "meta", "jsonb");
        info.addColumn(TABLE, "body_vec", "tsvector");
        return info;
    }

    private static Field field(String query) {
        Document doc = Parser.parse(query);
        OperationDefinition op = doc.getDefinitionsOfType(OperationDefinition.class).getFirst();
        return (Field) op.getSelectionSet().getSelections().getFirst();
    }

    private static List<String> build(SqlDialect dialect, String query) {
        return build(dialect, query, Map.of(), RlsOp.SELECT);
    }

    private static List<String> build(SqlDialect dialect, String query, Map<String, Object> variables, RlsOp op) {
        return build(dialect, query, variables, op, new HashMap<>());
    }

    private static List<String> build(SqlDialect dialect, String query, Map<String, Object> variables, RlsOp op,
                                      Map<String, Object> params) {
        FilterBuilder builder = new FilterBuilder(dialect, 100, schema(), "public");
        List<String> conditions = new ArrayList<>();
        ScopedValue.where(FilterBuilder.CURRENT_VARIABLES, variables)
                .run(() -> builder.buildWhereConditions(field(query), "t", params, conditions, TABLE, op));
        return conditions;
    }

    @Nested
    class OperatorsTheDialectCannotRun {

        @Test
        void regexOnADialectWithoutRegexIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { title: { regex: \"^a\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'regex'")
                    .hasMessageContaining("'title'");
        }

        @Test
        void fullTextSearchOnADialectWithoutItIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { body_vec: { search: \"fox\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'search'");
        }

        @Test
        void jsonPredicatesOnADialectWithoutThemAreRefused() {
            for (String filter : List.of("contains: { a: 1 }", "containedBy: { a: 1 }", "hasKey: \"a\"",
                    "hasKeys: [\"a\"]", "hasAnyKeys: [\"a\"]")) {
                assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { meta: { " + filter + " } }) { id } }"))
                        .as(filter)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("'meta'");
            }
        }

        @Test
        void anAdvertisedOperatorWhoseHookIsMissingIsRefusedNotDropped() {
            SqlDialect advertisesEverything = new PortableDialect(FilterCapabilities.ALL);
            assertThatThrownBy(() -> build(advertisesEverything, "{ docs(where: { title: { iregex: \"a\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'iregex'");
        }

        @Test
        void anOperatorOutsideTheColumnTypesCapabilitiesIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(FilterCapabilities.ALL),
                    "{ docs(where: { id: { like: \"1%\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'like'")
                    .hasMessageContaining("'id'");
        }

        @Test
        void theDialectsReasonIsPartOfTheRefusal() {
            FilterCapabilities explained = new FilterCapabilities() {
                @Override public List<String> operators(FilterColumnKind kind) { return FilterCapabilities.PORTABLE.operators(kind); }
                @Override public Set<String> restOperators() { return FilterCapabilities.PORTABLE.restOperators(); }
                @Override public String unsupportedReason(FilterColumnKind kind, String operator) { return "needs an index"; }
            };
            assertThatThrownBy(() -> build(new PortableDialect(explained), "{ docs(where: { title: { search: \"x\" } }) { id } }"))
                    .hasMessageContaining("needs an index");
        }

        @Test
        void anUnknownOperatorIsRefusedNotTurnedIntoEquality() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { title: { someCustom: \"x\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'someCustom'");
        }

        @Test
        void anUnknownIsValueIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { title: { is: MAYBE } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class MalformedShapes {

        @Test
        void aNonListInIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { id: { in: 1 } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'in'");
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { id: { notIn: \"1\" } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aNonObjectWhereIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: \"id = 1\") { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("where");
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: [{ id: { eq: 1 } }]) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aNonObjectColumnFilterIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { id: 1 }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'id'");
        }

        @Test
        void logicalOperatorsTakeObjects() {
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { or: [1, 2] }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { and: \"x\" }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> build(new PortableDialect(), "{ docs(where: { not: [{ id: { eq: 1 } }] }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void aSingleObjectOrIsAListOfOne() {
            assertThat(build(new PortableDialect(), "{ docs(where: { or: { id: { eq: 1 } } }) { id } }"))
                    .singleElement().asString().contains("\"id\" = :");
        }
    }

    @Nested
    class WhereAsAVariable {

        @Test
        void aWholeWhereVariableIsApplied() {
            Map<String, Object> where = Map.of("id", Map.of("eq", 7));
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = build(new PortableDialect(), "query($w: DocsWhereInput) { docs(where: $w) { id } }",
                    Map.of("w", where), RlsOp.SELECT, params);

            assertThat(conditions).singleElement().asString().contains("t.\"id\" = :");
            assertThat(params).containsValue(7);
        }

        @Test
        void aWholeWhereVariableWithNestedLogicIsApplied() {
            Map<String, Object> where = Map.of("or", List.of(
                    Map.of("title", Map.of("eq", "a")),
                    Map.of("id", Map.of("in", List.of(1, 2)))));
            List<String> conditions = build(new PortableDialect(), "query($w: DocsWhereInput) { docs(where: $w) { id } }",
                    Map.of("w", where), RlsOp.SELECT);

            assertThat(conditions).singleElement().asString()
                    .contains("t.\"title\" = :").contains(" OR ").contains("t.\"id\" IN (");
        }

        @Test
        void aColumnFilterVariableIsApplied() {
            List<String> conditions = build(new PortableDialect(),
                    "query($f: StringFilterInput) { docs(where: { title: $f }) { id } }",
                    Map.of("f", Map.of("neq", "x")), RlsOp.SELECT);

            assertThat(conditions).singleElement().asString().contains("t.\"title\" != :");
        }

        @Test
        void anInListVariableIsApplied() {
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = build(new PortableDialect(), "query($ids: [Int]) { docs(where: { id: { in: $ids } }) { id } }",
                    Map.of("ids", List.of(1, 2, 3)), RlsOp.SELECT, params);

            assertThat(conditions).singleElement().asString().contains("IN (");
            assertThat(params).hasSize(3);
        }

        @Test
        void aBigIntegerKeepsItsValue() {
            Map<String, Object> params = new HashMap<>();
            build(new PortableDialect(), "query($w: DocsWhereInput) { docs(where: $w) { id } }",
                    Map.of("w", Map.of("id", Map.of("eq", 9_999_999_999L))), RlsOp.SELECT, params);

            assertThat(params).containsValue(9_999_999_999L);
        }

        @Test
        void anUnsetWhereVariableReadsWithoutAFilter() {
            assertThat(build(new PortableDialect(), "query($w: DocsWhereInput) { docs(where: $w) { id } }")).isEmpty();
        }

        @Test
        void aNullWhereIsRefusedForAWrite() {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("w", null);
            for (RlsOp op : List.of(RlsOp.UPDATE, RlsOp.DELETE)) {
                assertThatThrownBy(() -> build(new PortableDialect(), "mutation($w: DocsWhereInput) { deleteDocs(where: $w) { id } }",
                        variables, op))
                        .as(op.name())
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("where");
            }
        }

        @Test
        void anOperatorVariableWithoutAValueIsRefused() {
            assertThatThrownBy(() -> build(new PortableDialect(), "query($x: Int) { docs(where: { id: { eq: $x } }) { id } }"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("$x");
        }
    }

    /** A dialect with only the engine's required methods: no regex, full-text or JSON operators. */
    static class PortableDialect implements SqlDialect {
        private final FilterCapabilities capabilities;

        PortableDialect() {
            this(FilterCapabilities.PORTABLE);
        }

        PortableDialect(FilterCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override public FilterCapabilities filterCapabilities() { return capabilities; }
        @Override public String buildObject(List<String> pairs) { return "obj(" + String.join(", ", pairs) + ")"; }
        @Override public String aggregateArray(String expr) { return "agg(" + expr + ")"; }
        @Override public String coalesceArray(String expr) { return "coalesce(" + expr + ")"; }
        @Override public String quoteIdentifier(String id) { return "\"" + id + "\""; }
        @Override public String qualifiedTable(String schema, String table) { return schema + ".\"" + table + "\""; }
        @Override public String encodeCursor(String expr) { return expr; }
        @Override public String ilike(String col, String param) { return col + " ILIKE " + param; }
        @Override public String orderByNulls(String col, String dir, String nulls) { return col + " " + dir; }
        @Override public String suffixCast(String type) { return ""; }
        @Override public String onConflict(List<String> cols, List<String> sets) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String alias, String suffix) { return alias + suffix; }
        @Override public String randAlias() { return "\"r\""; }
        @Override public String distinctOn(List<String> cols, String alias) { return ""; }
        @Override public Optional<String> regexSql(String colRef, String paramRef, boolean caseInsensitive) {
            return Optional.empty();
        }
    }
}
