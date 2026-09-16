package io.github.excalibase.compiler;

import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.GrantDeniedException;
import io.github.excalibase.security.RlsContext;
import io.github.excalibase.security.RlsOp;
import io.github.excalibase.security.TableGrantContributor;
import io.github.excalibase.spi.MutationCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the grant (exposure) layer at its compiler chokepoint: a table with no
 * grant is refused before the query compiler emits any SQL for it, and before
 * the row-level contributor is ever consulted.
 */
class SqlCompilerGrantTest {

    @AfterEach
    void clearContext() {
        RlsContext.clear();
    }

    @Test
    @DisplayName("no grant contributor registered (standalone) leaves compilation untouched")
    void compile_withoutGrantContributor_compilesNormally() {
        SqlCompiler compiler = compiler();

        assertThatCode(() -> compiler.compile("{ publicDocs { id } }")).doesNotThrowAnyException();
    }

    @Test
    void compile_tableWithoutGrant_isDenied() {
        RlsContext.setGrants(grants(Set.of()));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("{ publicDocs { id } }"))
                .isInstanceOf(GrantDeniedException.class)
                .hasMessageContaining("public.docs");
    }

    @Test
    void compile_grantedTable_returnsSql() {
        RlsContext.setGrants(grants(Set.of("public.docs:SELECT")));
        SqlCompiler compiler = compiler();

        assertThat(compiler.compile("{ publicDocs { id } }").sql()).contains("public.\"docs\"");
    }

    @Test
    @DisplayName("grant denial short-circuits before the row-policy contributor is consulted")
    void compile_tableWithoutGrant_deniesBeforeRowPoliciesAreConsulted() {
        AtomicBoolean rowPolicyConsulted = new AtomicBoolean(false);
        RlsContext.set((table, op) -> {
            rowPolicyConsulted.set(true);
            return null;
        });
        RlsContext.setGrants(grants(Set.of()));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("{ publicDocs { id } }"))
                .isInstanceOf(GrantDeniedException.class);
        assertThat(rowPolicyConsulted).isFalse();
    }

    @Test
    void compile_connectionSurface_isAlsoDenied() {
        RlsContext.setGrants(grants(Set.of()));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("{ publicDocsConnection { edges { node { id } } } }"))
                .isInstanceOf(GrantDeniedException.class);
    }

    @Test
    void compile_aggregateSurface_isAlsoDenied() {
        RlsContext.setGrants(grants(Set.of()));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("{ publicDocsAggregate { count } }"))
                .isInstanceOf(GrantDeniedException.class);
    }

    @Test
    @DisplayName("an embedded relation to an ungranted table is denied even when the parent is granted")
    void compile_nestedEmbedWithoutGrant_isDenied() {
        RlsContext.setGrants(grants(Set.of("public.orders:SELECT")));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("{ publicOrders { id publicDocsId { id } } }"))
                .isInstanceOf(GrantDeniedException.class)
                .hasMessageContaining("public.docs");
    }

    @Test
    void compile_insertWithSelectOnlyGrant_isDenied() {
        RlsContext.setGrants(grants(Set.of("public.docs:SELECT")));
        RecordingMutationCompiler mutations = new RecordingMutationCompiler();
        SqlCompiler compiler = compiler(mutations);

        assertThatThrownBy(() -> compiler.compile("mutation { createPublicDocs(input: { id: 1 }) { id } }"))
                .isInstanceOf(GrantDeniedException.class)
                .hasMessageContaining("INSERT");
        assertThat(mutations.invoked).isFalse();
    }

    @Test
    void compile_insertWithInsertGrant_isAllowed() {
        RlsContext.setGrants(grants(Set.of("public.docs:INSERT")));
        RecordingMutationCompiler mutations = new RecordingMutationCompiler();
        SqlCompiler compiler = compiler(mutations);

        assertThatCode(() -> compiler.compile("mutation { createPublicDocs(input: { id: 1 }) { id } }"))
                .doesNotThrowAnyException();
        assertThat(mutations.invoked).isTrue();
    }

    @Test
    void compile_deleteWithSelectOnlyGrant_isDenied() {
        RlsContext.setGrants(grants(Set.of("public.docs:SELECT")));
        SqlCompiler compiler = compiler();

        assertThatThrownBy(() -> compiler.compile("mutation { deletePublicDocs(id: 1) }"))
                .isInstanceOf(GrantDeniedException.class)
                .hasMessageContaining("DELETE");
    }

    /** Grants named "<table>:<OP>"; anything absent is denied. */
    private static TableGrantContributor grants(Set<String> allowed) {
        Set<String> granted = new HashSet<>(allowed);
        return (table, op) -> granted.contains(table + ":" + op.name());
    }

    private static SqlCompiler compiler() {
        return compiler(new RecordingMutationCompiler());
    }

    private static SqlCompiler compiler(MutationCompiler mutationCompiler) {
        return new SqlCompiler(schema(), "public", 30, new TestDialect(), mutationCompiler);
    }

    private static SchemaInfo schema() {
        SchemaInfo info = new SchemaInfo();
        info.addColumn("public.docs", "id", "integer");
        info.addPrimaryKey("public.docs", "id");
        info.setTableSchema("public.docs", "public");

        info.addColumn("public.orders", "id", "integer");
        info.addColumn("public.orders", "docs_id", "integer");
        info.addPrimaryKey("public.orders", "id");
        info.setTableSchema("public.orders", "public");
        info.addForeignKey("public.orders", "docs_id", "public.docs", "id");
        return info;
    }

    private static class RecordingMutationCompiler implements MutationCompiler {
        private boolean invoked;

        @Override
        public SqlCompiler.CompiledQuery compileMutation(
                graphql.language.Field field, String fieldName,
                Map<String, Object> params, Map<String, Object> variables, MutationBuilder shared) {
            invoked = true;
            return new SqlCompiler.CompiledQuery("SELECT 1", params);
        }

        @Override
        public SqlCompiler.CompiledQuery compileMutationFragment(
                graphql.language.Field field, String fieldName,
                Map<String, Object> params, Map<String, Object> variables, MutationBuilder shared) {
            invoked = true;
            return new SqlCompiler.CompiledQuery("WITH x AS (INSERT) SELECT 1", params);
        }
    }

    private static class TestDialect implements SqlDialect {
        @Override public String buildObject(List<String> pairs) {
            return "jsonb_build_object(" + String.join(", ", pairs) + ")";
        }
        @Override public String aggregateArray(String expr) { return "jsonb_agg(" + expr + ")"; }
        @Override public String coalesceArray(String expr) { return "coalesce(" + expr + ", '[]'::jsonb)"; }
        @Override public String quoteIdentifier(String id) { return "\"" + id + "\""; }
        @Override public String qualifiedTable(String schema, String table) {
            return schema + ".\"" + table + "\"";
        }
        @Override public String encodeCursor(String expr) { return "encode(" + expr + ")"; }
        @Override public String ilike(String col, String param) { return col + " ILIKE " + param; }
        @Override public String orderByNulls(String col, String dir, String nulls) { return col + " " + dir; }
        @Override public String suffixCast(String type) { return ""; }
        @Override public String onConflict(List<String> cols, List<String> sets) { return ""; }
        @Override public boolean supportsReturning() { return true; }
        @Override public String cteName(String alias, String suffix) { return "\"" + alias + "_" + suffix + "\""; }
        @Override public String randAlias() { return "\"sub" + Math.abs(System.nanoTime() % 100000) + "\""; }
        @Override public String distinctOn(List<String> cols, String alias) { return ""; }
        @Override public String wrapMutationResult(String sql, String fieldName) { return sql; }
    }
}
