package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.ExposedFunctions;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.RlsWhereContributor;
import io.github.excalibase.spi.MutationCompiler;
import graphql.language.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How a tracked function compiles: a named call as the FROM source, read like its return table. */
class FunctionCompileTest {

    private static final String NOTES = "public.notes";

    private static final ExposedFunction SEARCH = new ExposedFunction("public.search_notes",
            ExposedFunction.Operation.QUERY, "publicSearchNotes", NOTES, true,
            List.of(new ExposedFunction.Argument("p_query", "text", true),
                    new ExposedFunction.Argument("p_limit", "integer", false)),
            new ExposedFunction.SessionArgument("session", "jsonb"), false);

    private static final ExposedFunction LATEST = new ExposedFunction("public.latest_note",
            ExposedFunction.Operation.QUERY, "publicLatestNote", NOTES, false, List.of(), null, false);

    private static final ExposedFunction ADD = new ExposedFunction("public.add_note",
            ExposedFunction.Operation.MUTATION, "publicAddNote", NOTES, true,
            List.of(new ExposedFunction.Argument("p_body", "text", true)), null, false);

    @AfterEach
    void clear() {
        RlsContext.clear();
    }

    private static SqlCompiler compiler() {
        SchemaInfo schema = new SchemaInfo();
        schema.addColumn(NOTES, "id", "integer");
        schema.addColumn(NOTES, "body", "text");
        schema.addPrimaryKey(NOTES, "id");
        schema.setTableSchema(NOTES, "public");
        schema.addComputedField(NOTES, "notes_label", "text");
        return new SqlCompiler(schema, "public", 30, new TestDialect(), new NoMutations(), 0, TableAccess.UNRESTRICTED,
                ExposedFunctions.of(List.of(SEARCH, LATEST, ADD)));
    }

    @Test
    void aQueryFunction_isCalledByName_withTheSessionAsJson() {
        RlsContext.setSessionVariables(Map.of("x-excalibase-user-id", "u-1", "x-excalibase-role", "user"));

        SqlCompiler.CompiledQuery compiled = compiler().compile(
                "{ publicSearchNotes(p_query: \"a%\", where: { id: { gt: 1 } }, limit: 5) { id body } }");

        assertThat(compiled.sql()).contains("public.\"search_notes\"(\"p_query\" => CAST(:fn_arg_0 AS text), "
                + "\"session\" => CAST(:fn_arg_1 AS jsonb))");
        assertThat(compiled.params()).containsEntry("fn_arg_0", "a%")
                .containsEntry("fn_arg_1", "{\"x-excalibase-role\":\"user\",\"x-excalibase-user-id\":\"u-1\"}");
        assertThat(compiled.sql()).contains("'publicSearchNotes'").contains("LIMIT");
    }

    @Test
    void theReturnTablesSelectFilter_appliesToTheRows() {
        RlsContext.set(new RlsWhereContributor() {
            @Override
            public Contribution contribute(String table, RlsOp op) {
                return new Contribution("RLS_" + table, Map.of());
            }
        });

        String sql = compiler().compile("{ publicSearchNotes(p_query: \"a\") { id } }").sql();

        assertThat(sql).contains("RLS_public.notes");
    }

    @Test
    void aSingleRowFunction_skipsTheAllNullRow() {
        String sql = compiler().compile("{ publicLatestNote { id } }").sql();

        assertThat(sql).contains("public.\"latest_note\"()").contains("IS NULL)").contains("LIMIT 1");
    }

    @Test
    void aMutationFunction_isAMutationField_only() {
        String sql = compiler().compile("mutation { added: publicAddNote(p_body: \"hi\") { id } }").sql();

        assertThat(sql).startsWith("SELECT jsonb_build_object('added', (").contains("public.\"add_note\"(");
        assertThatThrownBy(() -> compiler().compile("{ publicAddNote(p_body: \"hi\") { id } }"))
                .hasMessageContaining("Unknown field");
    }

    @Test
    void unknownOrMissingArguments_areRefused() {
        assertThatThrownBy(() -> compiler().compile("{ publicSearchNotes(p_query: \"a\", session: \"{}\") { id } }"))
                .hasMessageContaining("Unknown argument session");
        assertThatThrownBy(() -> compiler().compile("{ publicSearchNotes { id } }"))
                .hasMessageContaining("Missing argument p_query");
    }

    @Test
    void aComputedField_isReflectedButNotServed() {
        assertThatThrownBy(() -> compiler().compile("{ publicNotes { id label } }"))
                .hasMessageContaining("Unknown field(s): label");
        assertThatThrownBy(() -> compiler().compile("{ publicNotes { id notes_label } }"))
                .hasMessageContaining("Unknown field(s): notes_label");
    }

    @Test
    void anUnknownRootMutationField_isAnError_neverAnEmptyResult() {
        assertThatThrownBy(() -> compiler().compile("mutation { nope(x: 1) { id } }"))
                .hasMessage("Unknown field(s): nope");
        assertThatThrownBy(() -> compiler().compile(
                "mutation { publicAddNote(p_body: \"hi\") { id } nope { id } }"))
                .hasMessage("Unknown field(s): nope");
    }

    @Test
    void aKnownMutationThatCannotCompile_isAnError_namingIt() {
        assertThatThrownBy(() -> compiler().compile("mutation { deletePublicNotes { id } }"))
                .hasMessage("Invalid arguments for deletePublicNotes");
    }

    @Test
    void anUnknownRootQueryField_isAnError_evenBesideAKnownOne() {
        assertThatThrownBy(() -> compiler().compile("{ nope { id } }")).hasMessage("Unknown field(s): nope");
        assertThatThrownBy(() -> compiler().compile("{ publicLatestNote { id } nope { id } }"))
                .hasMessage("Unknown field(s): nope");
    }

    private static final class NoMutations implements MutationCompiler {
        @Override
        public SqlCompiler.CompiledQuery compileMutation(Field field, String fieldName, Map<String, Object> params,
                                                         Map<String, Object> variables, MutationBuilder shared) {
            return null;
        }
    }

    private static final class TestDialect implements SqlDialect {
        @Override public String buildObject(List<String> pairs) {
            return "jsonb_build_object(" + String.join(", ", pairs) + ")";
        }
        @Override public String aggregateArray(String expr) { return "jsonb_agg(" + expr + ")"; }
        @Override public String coalesceArray(String expr) { return "coalesce(" + expr + ", '[]'::jsonb)"; }
        @Override public String quoteIdentifier(String id) { return "\"" + id + "\""; }
        @Override public String qualifiedTable(String schema, String table) { return schema + ".\"" + table + "\""; }
        @Override public String encodeCursor(String expr) { return "encode(" + expr + ")"; }
        @Override public String ilike(String col, String param) { return col + " ILIKE " + param; }
        @Override public String orderByNulls(String col, String dir, String nulls) { return col + " " + dir; }
        @Override public String suffixCast(String type) { return ""; }
        @Override public String onConflict(List<String> cols, List<String> sets) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String alias, String suffix) { return "\"" + alias + "_" + suffix + "\""; }
        @Override public String randAlias() { return "\"t" + Math.abs(System.nanoTime() % 100000) + "\""; }
        @Override public String distinctOn(List<String> cols, String alias) { return ""; }
    }
}
