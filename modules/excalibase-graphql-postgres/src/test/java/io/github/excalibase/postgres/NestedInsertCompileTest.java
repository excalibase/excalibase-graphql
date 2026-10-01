package io.github.excalibase.postgres;

import io.github.excalibase.compiler.MutationSequence;
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

/**
 * Nested inserts compile to ordered statements, as Hasura runs them: the rows an object relationship
 * points at first, then the row, then the rows of its array relationships, each statement holding its
 * own table's check. Anything the role cannot insert is refused before a statement exists.
 */
class NestedInsertCompileTest {

    private static final String CUSTOMERS = "public.customers";
    private static final String ORDERS = "public.orders";
    private static final String ITEMS = "public.order_items";
    private static final String NOTES = "public.item_notes";

    /** customers ← orders ← order_items ← item_notes; orders readable, the rest insert-only. */
    private static SqlCompiler compiler(boolean ordersReadable) {
        SchemaInfo view = new SchemaInfo();
        table(view, CUSTOMERS, false, "id", "integer", "name", "text");
        table(view, ORDERS, ordersReadable, "id", "integer", "customer_id", "integer", "note", "text");
        table(view, ITEMS, false, "id", "integer", "order_id", "integer", "sku", "text", "qty", "integer");
        table(view, NOTES, false, "id", "integer", "item_id", "integer", "body", "text");
        view.setInsertRelationships(
                Map.of(ORDERS + ".publicOrderItems", new SchemaInfo.ReverseFkInfo(ITEMS, List.of("order_id"), List.of("id")),
                        ITEMS + ".publicItemNotes", new SchemaInfo.ReverseFkInfo(NOTES, List.of("item_id"), List.of("id"))),
                Map.of(ORDERS + ".publicCustomerId", new SchemaInfo.FkInfo(List.of("customer_id"), CUSTOMERS, List.of("id"))));
        TableAccess access = TableAccess.enforcing(Map.of(
                CUSTOMERS, rights(false, "name"),
                ORDERS, rights(ordersReadable, "customer_id", "note"),
                ITEMS, rights(false, "order_id", "sku", "qty"),
                NOTES, rights(false, "body")));
        return new SqlCompiler(view, "public", 30, new PostgresDialect(), new PostgresMutationCompiler(), 0, access);
    }

    private static void table(SchemaInfo view, String table, boolean readable, String... namesAndTypes) {
        view.setTableSchema(table, "public");
        for (int i = 0; i < namesAndTypes.length; i += 2) {
            if (readable) {
                view.addColumn(table, namesAndTypes[i], namesAndTypes[i + 1]);
            } else {
                view.addWriteOnlyColumn(table, namesAndTypes[i], namesAndTypes[i + 1]);
            }
        }
        if (!readable) {
            view.addWriteOnlyTable(table);
        }
    }

    private static TableAccess.Rights rights(boolean readable, String... insertColumns) {
        Set<RlsOp> operations = readable ? Set.of(RlsOp.SELECT, RlsOp.INSERT) : Set.of(RlsOp.INSERT);
        return new TableAccess.Rights(operations, Set.of(insertColumns), Set.of(), null, false);
    }

    private static MutationSequence.NestedInsert onlyStep(SqlCompiler.CompiledQuery compiled) {
        assertThat(compiled.sequence()).isNotNull();
        assertThat(compiled.sequence().steps()).hasSize(1);
        return (MutationSequence.NestedInsert) compiled.sequence().steps().getFirst();
    }

    @Test
    void theRowThenItsChildren_eachAStatementOfItsOwn() {
        MutationSequence.NestedInsert step = onlyStep(compiler(false).compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\", qty: 1 }, { sku: \"b\", qty: 2 }] } })"
                + " { affected_rows } }"));

        assertThat(step.responseKey()).isEqualTo("createPublicOrders");
        assertThat(step.roots()).hasSize(1);
        MutationSequence.InsertNode order = step.roots().getFirst();
        assertThat(order.sql()).contains("INSERT INTO \"public\".\"orders\"");
        assertThat(order.parentRowParam()).isNull();
        assertThat(order.after()).hasSize(1);
        MutationSequence.InsertNode items = order.after().getFirst();
        assertThat(items.sql()).contains("INSERT INTO \"public\".\"order_items\"")
                .contains("jsonb_populate_record(NULL::\"public\".\"orders\", CAST(:" + items.parentRowParam())
                .doesNotContain("UNION");
        assertThat(items.after()).isEmpty();
        assertThat(step.outputSql()).contains("'affected_rows', CAST(:" + step.countParam() + " AS integer)");
    }

    @Test
    void aChildWithItsOwnNestedRows_isInsertedOneRowAtATime() {
        MutationSequence.NestedInsert step = onlyStep(compiler(false).compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\", publicItemNotes: { data: [{ body: \"x\" }] } },"
                + " { sku: \"b\" }] } }) { affected_rows } }"));

        List<MutationSequence.InsertNode> items = step.roots().getFirst().after();
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().after()).hasSize(1);
        assertThat(items.getFirst().after().getFirst().sql()).contains("INSERT INTO \"public\".\"item_notes\"");
        assertThat(items.get(1).after()).isEmpty();
    }

    @Test
    void anObjectRelationship_isInsertedFirst_andFillsTheForeignKey() {
        MutationSequence.NestedInsert step = onlyStep(compiler(false).compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicCustomerId: { data: { name: \"c\" } } }) { affected_rows } }"));

        MutationSequence.InsertNode order = step.roots().getFirst();
        assertThat(order.before()).hasSize(1);
        MutationSequence.Before customer = order.before().getFirst();
        assertThat(customer.relationship()).isEqualTo("publicCustomerId");
        assertThat(customer.node().sql()).contains("INSERT INTO \"public\".\"customers\"");
        assertThat(order.sql()).contains("\"customer_id\"")
                .contains("jsonb_populate_record(NULL::\"public\".\"customers\", CAST(:" + customer.rowParam());
    }

    @Test
    void createMany_withNestedRows_insertsEachRowWithItsChildren() {
        MutationSequence.NestedInsert step = onlyStep(compiler(false).compile("mutation { createManyPublicOrders(inputs: ["
                + "{ note: \"one\", publicOrderItems: { data: [{ sku: \"a\" }] } }, { note: \"two\" }]) { affected_rows } }"));

        assertThat(step.roots()).hasSize(2);
        assertThat(step.roots().getFirst().after()).hasSize(1);
        assertThat(step.roots().get(1).after()).isEmpty();
    }

    @Test
    void theInputMayComeFromVariables() {
        Map<String, Object> input = Map.of("note", "v", "publicOrderItems",
                Map.of("data", List.of(Map.of("sku", "a"))));
        MutationSequence.NestedInsert step = onlyStep(compiler(false).compile(
                "mutation($input: PublicOrdersCreateInput!) { createPublicOrders(input: $input) { affected_rows } }",
                Map.of("input", input)));

        assertThat(step.roots().getFirst().after()).hasSize(1);
        assertThat(step.roots().getFirst().after().getFirst().sql()).contains("\"order_items\"");
    }

    @Test
    void aReadableParent_answersItsRow_filteredAfterEveryInsert() {
        MutationSequence.NestedInsert step = onlyStep(compiler(true).compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\" }] } }) { id note } }"));

        assertThat(step.outputSql()).contains("jsonb_populate_recordset(NULL::\"public\".\"orders\", CAST(:"
                + step.rowsParam() + " AS jsonb))").contains("'note'");
    }

    @Test
    void anUnreadableChild_isNoFieldOfTheReadableParent() {
        SqlCompiler compiler = compiler(true);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\" }] } }) { id publicOrderItems { sku } } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown field(s): publicOrderItems");
    }

    @Test
    void theForeignKeyFilledByTheParent_cannotBeSent() {
        SqlCompiler compiler = compiler(false);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\", order_id: 9 }] } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cannot insert \"order_id\" columns as their values are already being determined by parent insert");
    }

    @Test
    void anObjectRelationshipBesideItsForeignKey_isRefused() {
        SqlCompiler compiler = compiler(false);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicOrders(input: {"
                + " customer_id: 3, publicCustomerId: { data: { name: \"c\" } } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cannot insert object relationship \"publicCustomerId\" as \"customer_id\" column values are already determined");
    }

    @Test
    void aChildColumnOutsideItsInsertPermission_isRefused() {
        SqlCompiler compiler = compiler(false);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { data: [{ sku: \"a\", id: 1 }] } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown column 'id' in insert of public.order_items");
    }

    @Test
    void aRelationshipTheRoleCannotInsertThrough_isNoField() {
        SqlCompiler compiler = compiler(false);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicCustomers(input: {"
                + " name: \"c\", publicOrders: { data: [{ note: \"n\" }] } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicOrders");
    }

    @Test
    void aRelationshipWithoutData_isRefused() {
        SqlCompiler compiler = compiler(false);
        assertThatThrownBy(() -> compiler.compile("mutation { createPublicOrders(input: {"
                + " note: \"n\", publicOrderItems: { } }) { affected_rows } }"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data");
    }

    @Test
    void anEmptyOrNullRelationship_isNoNestedInsert() {
        for (String relation : List.of("{ data: [] }", "null")) {
            SqlCompiler.CompiledQuery compiled = compiler(false).compile("mutation { createPublicOrders(input: {"
                    + " note: \"n\", publicOrderItems: " + relation + " }) { affected_rows } }");

            assertThat(compiled.sequence()).isNull();
            assertThat(compiled.sql()).contains("INSERT INTO \"public\".\"orders\"").doesNotContain("order_items");
        }
    }

    @Test
    void createMany_withOnlyEmptyRelationships_staysOneStatement() {
        SqlCompiler.CompiledQuery compiled = compiler(false).compile("mutation { createManyPublicOrders(inputs: ["
                + "{ note: \"one\", publicOrderItems: { data: [] } }, { note: \"two\", publicOrderItems: null }])"
                + " { affected_rows } }");

        assertThat(compiled.sequence()).isNull();
        assertThat(compiled.sql()).contains("INSERT INTO \"public\".\"orders\"").doesNotContain("order_items");
    }

    @Test
    void aNestedInsertBesideOtherFields_runsEveryFieldInOrder() {
        SqlCompiler.CompiledQuery compiled = compiler(false).compile("mutation {"
                + " first: createPublicCustomers(input: { name: \"c\" }) { affected_rows }"
                + " second: createPublicOrders(input: { note: \"n\", publicOrderItems: { data: [{ sku: \"a\" }] } }) { affected_rows } }");

        List<MutationSequence.Step> steps = compiled.sequence().steps();
        assertThat(steps).extracting(MutationSequence.Step::responseKey).containsExactly("first", "second");
        assertThat(steps.getFirst()).isInstanceOf(MutationSequence.Statement.class);
        assertThat(((MutationSequence.Statement) steps.getFirst()).sql()).contains("\"customers\"");
        assertThat(steps.get(1)).isInstanceOf(MutationSequence.NestedInsert.class);
    }
}
