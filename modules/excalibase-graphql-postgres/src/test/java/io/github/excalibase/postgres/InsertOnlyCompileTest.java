package io.github.excalibase.postgres;

import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A role that may insert into a table but not select it writes rows and learns only how many. */
class InsertOnlyCompileTest {

    private static final String MESSAGES = "public.messages";

    private static SqlCompiler compiler() {
        SchemaInfo view = new SchemaInfo();
        view.setTableSchema(MESSAGES, "public");
        view.addWriteOnlyColumn(MESSAGES, "email", "text");
        view.addWriteOnlyColumn(MESSAGES, "body", "text");
        view.addWriteOnlyTable(MESSAGES);
        TableAccess access = TableAccess.enforcing(Map.of(MESSAGES, new TableAccess.Rights(Set.of(RlsOp.INSERT),
                Set.of("email", "body"), Set.of(), null, false)));
        return new SqlCompiler(view, "public", 30, new PostgresDialect(), new PostgresMutationCompiler(), 0, access);
    }

    @Test
    void create_countsTheWrittenRows_withoutTheSelectFilter() {
        SqlCompiler.CompiledQuery compiled = compiler().compile(
                "mutation { createPublicMessages(input: { email: \"a@b.c\", body: \"hi\" }) { __typename affected_rows } }");

        assertThat(compiled.sql()).contains("INSERT INTO \"public\".\"messages\"")
                .contains("'affected_rows', count(*)")
                .doesNotContain("FALSE");
        assertThat(compiled.params()).containsValues("a@b.c", "hi");
    }

    @Test
    void createMany_countsEveryWrittenRow() {
        SqlCompiler.CompiledQuery compiled = compiler().compile("mutation { createManyPublicMessages(inputs: ["
                + "{ email: \"a@b.c\", body: \"one\" }, { email: \"d@e.f\", body: \"two\" }]) { affected_rows } }");

        assertThat(compiled.sql()).contains("'affected_rows', count(*)").doesNotContain("jsonb_agg");
    }

    @Test
    void createAndMultipleInserts_combineIntoOneStatement() {
        SqlCompiler.CompiledQuery compiled = compiler().compile("mutation { "
                + "one: createPublicMessages(input: { body: \"x\" }) { affected_rows } "
                + "two: createPublicMessages(input: { body: \"y\" }) { affected_rows } }");

        assertThat(compiled.sql()).contains("'one'").contains("'two'");
    }

    @Test
    void aRowField_isUnknown() {
        assertThatThrownBy(() -> compiler().compile(
                "mutation { createPublicMessages(input: { body: \"x\" }) { affected_rows email } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown field(s): email");
    }

    @Test
    void theTable_hasNoQueryField() {
        for (String query : List.of("{ publicMessages { email } }", "{ publicMessagesConnection { edges { cursor } } }",
                "{ publicMessagesAggregate { count } }")) {
            assertThatThrownBy(() -> compiler().compile(query))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown field(s)");
        }
    }

    @Test
    void updateAndDelete_doNotExist() {
        for (String query : List.of("mutation { updatePublicMessages(where: { body: { eq: \"x\" } }, input: { body: \"y\" }) { affected_rows } }",
                "mutation { deletePublicMessages(where: { body: { eq: \"x\" } }) { affected_rows } }")) {
            assertThatThrownBy(() -> compiler().compile(query))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown field(s)");
        }
    }

    @Test
    void onConflict_isNotOffered() {
        assertThatThrownBy(() -> compiler().compile("mutation { createPublicMessages(input: { body: \"x\" }, "
                + "onConflict: { constraint: \"messages_pkey\", update_columns: [\"body\"] }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onConflict");
    }

    @Test
    void aColumnOutsideTheInsertPermission_isRefused() {
        assertThatThrownBy(() -> compiler().compile(
                "mutation { createPublicMessages(input: { body: \"x\", source: \"forged\" }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void aNestedObjectThatIsNoRelationship_isRefused_notDropped() {
        assertThatThrownBy(() -> compiler().compile(
                "mutation { createPublicMessages(input: { body: \"x\", replies: { data: [{ body: \"y\" }] } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replies");
    }
}
