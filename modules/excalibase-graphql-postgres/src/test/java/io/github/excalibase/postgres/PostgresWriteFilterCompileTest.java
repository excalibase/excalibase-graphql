package io.github.excalibase.postgres;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A write reaches only the rows its filter names, whether the filter is inline or a variable. */
class PostgresWriteFilterCompileTest {

    private static final String ITEMS = "public.items";

    private static SqlCompiler compiler() {
        SchemaInfo schema = new SchemaInfo();
        schema.setTableSchema(ITEMS, "public");
        schema.addColumn(ITEMS, "id", "integer");
        schema.addColumn(ITEMS, "name", "text");
        schema.addPrimaryKey(ITEMS, "id");
        return new SqlCompiler(schema, "public", 30, new PostgresDialect(), new PostgresMutationCompiler());
    }

    private static Map<String, Object> variable(String name, Object value) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(name, value);
        return variables;
    }

    @Test
    void deleteWithAWhereVariableDeletesOnlyTheNamedRows() {
        SqlCompiler.CompiledQuery compiled = compiler().compile(
                "mutation($w: PublicItemsWhereInput) { deletePublicItems(where: $w) { id } }",
                variable("w", Map.of("id", Map.of("eq", 1))));

        assertThat(compiled.sql()).contains("DELETE FROM").contains(".\"id\" = :");
        assertThat(compiled.params()).containsValue(1);
    }

    @Test
    void updateWithAWhereVariableUpdatesOnlyTheNamedRows() {
        SqlCompiler.CompiledQuery compiled = compiler().compile(
                "mutation($w: PublicItemsWhereInput) { updatePublicItems(where: $w, input: { name: \"x\" }) { id } }",
                variable("w", Map.of("name", Map.of("eq", "old"))));

        assertThat(compiled.sql()).contains("UPDATE").contains(".\"name\" = :");
    }

    @Test
    void aNullWhereVariableIsRefusedForADelete() {
        assertThatThrownBy(() -> compiler().compile(
                "mutation($w: PublicItemsWhereInput) { deletePublicItems(where: $w) { id } }", variable("w", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("where");
    }

    @Test
    void collectionWritesApplyAFilterVariable() {
        Map<String, Object> filter = variable("f", Map.of("id", Map.of("in", List.of(1, 2))));
        SqlCompiler.CompiledQuery update = compiler().compile("mutation($f: PublicItemsWhereInput) { "
                + "updatePublicItemsCollection(set: { name: \"x\" }, filter: $f, atMost: 2) { id } }", filter);
        SqlCompiler.CompiledQuery delete = compiler().compile("mutation($f: PublicItemsWhereInput) { "
                + "deleteFromPublicItemsCollection(filter: $f, atMost: 2) { id } }", filter);

        assertThat(update.sql()).contains(".\"id\" IN (");
        assertThat(delete.sql()).contains(".\"id\" IN (");
    }

    @Test
    void anArgumentTheMutationDoesNotTakeIsRefused() {
        assertThatThrownBy(() -> compiler().compile(
                "mutation { createManyPublicItems(inputs: [{ name: \"a\" }], onConflict: { constraint: \"items_pkey\", "
                        + "update_columns: [\"name\"] }) { id } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'onConflict'");
        assertThatThrownBy(() -> compiler().compile(
                "mutation { updatePublicItemsCollection(set: { name: \"x\" }, where: { id: { eq: 1 } }) { id } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'where'");
        assertThatThrownBy(() -> compiler().compile("mutation { createPublicItems(input: { name: \"a\" }, dryRun: true) { id } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'dryRun'");
    }

    @Test
    void theArgumentsEachMutationTakesStillCompile() {
        SqlCompiler compiler = compiler();
        assertThat(compiler.compile("mutation { createPublicItems(input: { name: \"a\" }, onConflict: { constraint: "
                + "\"items_pkey\", update_columns: [\"name\"] }) { id } }").sql()).contains("ON CONFLICT");
        assertThat(compiler.compile("mutation { deletePublicItems(filter: { id: { eq: 1 } }) { id } }").sql())
                .contains("DELETE FROM");
        assertThat(compiler.compile("mutation { deleteFromPublicItemsCollection(filter: { id: { eq: 1 } }, atMost: 1) "
                + "{ id } }").sql()).contains("DELETE FROM");
    }

    @Test
    void anArgumentAReadDoesNotTakeIsRefused() {
        assertThatThrownBy(() -> compiler().compile("{ publicItems(where: { id: { eq: 1 } }, after: \"x\") { id } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'after'");
        assertThatThrownBy(() -> compiler().compile("{ publicItemsAggregate(limit: 1) { count } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'limit'");
        assertThatThrownBy(() -> compiler().compile("{ publicItemsConnection(offset: 1) { edges { node { id } } } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'offset'");
    }

    @Test
    void theArgumentsEachReadTakesStillCompile() {
        SqlCompiler compiler = compiler();
        assertThat(compiler.compile("{ publicItems(where: { id: { eq: 1 } }, orderBy: { id: ASC }, limit: 2, offset: 1) "
                + "{ id } }").sql()).contains("LIMIT");
        assertThat(compiler.compile("{ publicItemsConnection(first: 1, after: \"MQ==\", where: { id: { gt: 0 } }, "
                + "orderBy: { id: ASC }) { edges { node { id } } } }").sql()).isNotBlank();
        assertThat(compiler.compile("{ publicItemsAggregate(where: { id: { gt: 0 } }) { count } }").sql()).isNotBlank();
    }
}
