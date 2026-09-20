package io.github.excalibase.rls;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.security.RlsOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureFilterTest {

    private static final String PROJECT = "proj-1";
    private static final String ROLE = "authenticated";

    private static SchemaInfo twoSchemaFixture() {
        SchemaInfo info = new SchemaInfo();
        addTable(info, "public", "orders", "id", "customer_id", "total");
        addTable(info, "public", "customer", "id", "name");
        addTable(info, "public", "secrets", "id", "token");
        addTable(info, "billing", "customer", "id", "plan");
        info.addForeignKey("public.orders", "customer_id", "public.customer", "id");
        return info;
    }

    private static void addTable(SchemaInfo info, String schema, String table, String... columns) {
        String key = schema + "." + table;
        for (String column : columns) {
            info.addColumn(key, column, "text");
        }
        info.setTableSchema(key, schema);
        info.addPrimaryKey(key, columns[0]);
    }

    private static TableGrants enforcing(TableGrant... grants) {
        return new TableGrants(PROJECT, true, List.of(grants));
    }

    private static TableGrant grant(String resource, Set<Operation> operations) {
        return new TableGrant("g-" + resource, PROJECT, resource, operations, ROLE, true);
    }

    @Nested
    @DisplayName("opt-in")
    class OptIn {

        @Test
        void apply_whenNotEnforced_returnsTheSchemaUntouched() {
            SchemaInfo source = twoSchemaFixture();

            ExposureFilter.Result result = ExposureFilter.apply(source, TableGrants.unenforced(PROJECT), ROLE);

            assertThat(result.schemaInfo()).isSameAs(source);
            assertThat(result.exposure().enforced()).isFalse();
        }

        @Test
        void apply_whenEnforcedWithNoGrants_removesEveryTable() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(), enforcing(), ROLE);

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
            assertThat(result.exposure().enforced()).isTrue();
        }
    }

    @Nested
    @DisplayName("table exposure")
    class Tables {

        @Test
        void apply_whenSelectGranted_keepsTableWithColumnsAndPrimaryKey() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), ROLE);

            SchemaInfo filtered = result.schemaInfo();
            assertThat(filtered.getTableNames()).containsExactly("public.orders");
            assertThat(filtered.getColumns("public.orders")).containsExactlyInAnyOrder("id", "customer_id", "total");
            assertThat(filtered.getPrimaryKeys("public.orders")).containsExactly("id");
            assertThat(filtered.getTableSchema("public.orders")).isEqualTo("public");
        }

        @Test
        void apply_whenSelectNotGranted_removesTableFromSchema() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().hasTable("public.secrets")).isFalse();
        }

        @Test
        void apply_whenOnlyInsertGranted_removesTableBecauseReadingIsRequiredToExposeIt() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.INSERT))), ROLE);

            assertThat(result.schemaInfo().hasTable("public.orders")).isFalse();
            assertThat(result.exposure().permits("public.orders", RlsOp.INSERT)).isFalse();
        }

        @Test
        void apply_whenGrantHasNoOperations_grantsNothing() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of())), ROLE);

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
        }

        @Test
        void apply_whenGrantDisabled_isIgnored() {
            TableGrant disabled = new TableGrant("g1", PROJECT, "public.orders",
                    Set.of(Operation.SELECT), ROLE, false);

            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(), enforcing(disabled), ROLE);

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
        }

        @Test
        void apply_whenGrantBelongsToAnotherRole_isIgnored() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), "anon");

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
        }

        @Test
        void apply_whenGrantHasNoRole_appliesToNobody() {
            TableGrant roleless = new TableGrant("g1", PROJECT, "public.orders",
                    Set.of(Operation.SELECT), null, true);

            assertThat(ExposureFilter.apply(twoSchemaFixture(), enforcing(roleless), "anon")
                    .schemaInfo().getTableNames()).isEmpty();
            assertThat(ExposureFilter.apply(twoSchemaFixture(), enforcing(roleless), ROLE)
                    .schemaInfo().getTableNames()).isEmpty();
        }

        @Test
        void apply_whenSeveralGrantsCoverOneTable_unionsTheirOperations() {
            TableGrant read = new TableGrant("g1", PROJECT, "public.orders", Set.of(Operation.SELECT), ROLE, true);
            TableGrant write = new TableGrant("g2", PROJECT, "public.orders", Set.of(Operation.UPDATE), ROLE, true);

            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(), enforcing(read, write), ROLE);

            assertThat(result.exposure().permits("public.orders", RlsOp.SELECT)).isTrue();
            assertThat(result.exposure().permits("public.orders", RlsOp.UPDATE)).isTrue();
            assertThat(result.exposure().permits("public.orders", RlsOp.DELETE)).isFalse();
        }
    }

    @Nested
    @DisplayName("resource resolution")
    class ResourceResolution {

        @Test
        void apply_whenBareResourceMatchesExactlyOneSchema_resolvesToTheCanonicalKey() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getTableNames()).containsExactly("public.orders");
        }

        @Test
        void apply_whenBareResourceMatchesSeveralSchemas_grantsNothing() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("customer", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
        }

        @Test
        void apply_whenResourceMatchesNothing_grantsNothing() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.ghost", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getTableNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("relationships")
    class Relationships {

        @Test
        void apply_whenBothSidesGranted_keepsTheForeignKey() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT)),
                            grant("public.customer", Set.of(Operation.SELECT))), ROLE);

            SchemaInfo filtered = result.schemaInfo();
            assertThat(filtered.getAllForwardFks()).isNotEmpty();
            assertThat(filtered.getAllReverseFks()).isNotEmpty();
        }

        @Test
        void apply_whenTheReferencedTableIsNotGranted_dropsTheForeignKey() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), ROLE);

            SchemaInfo filtered = result.schemaInfo();
            assertThat(filtered.getAllForwardFks()).isEmpty();
            assertThat(filtered.getAllReverseFks()).isEmpty();
        }

        @Test
        void apply_whenTheChildTableIsNotGranted_dropsTheReverseRelation() {
            ExposureFilter.Result result = ExposureFilter.apply(twoSchemaFixture(),
                    enforcing(grant("public.customer", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getAllReverseFks()).isEmpty();
        }
    }

    @Nested
    @DisplayName("views")
    class Views {

        @Test
        void apply_whenViewGranted_keepsItMarkedAsAView() {
            SchemaInfo source = twoSchemaFixture();
            addTable(source, "public", "order_summary", "id", "total");
            source.addView("public.order_summary");

            ExposureFilter.Result result = ExposureFilter.apply(source,
                    enforcing(grant("public.order_summary", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().isView("public.order_summary")).isTrue();
        }

        @Test
        void apply_whenViewNotGranted_dropsItFromTheViewSet() {
            SchemaInfo source = twoSchemaFixture();
            addTable(source, "public", "order_summary", "id", "total");
            source.addView("public.order_summary");

            ExposureFilter.Result result = ExposureFilter.apply(source,
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getViewNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("functions")
    class Functions {

        private SchemaInfo withFunction(String returnType) {
            SchemaInfo info = twoSchemaFixture();
            info.addStoredProcedure("public.recent_orders",
                    new SchemaInfo.ProcedureInfo("recent_orders", List.of(), returnType));
            return info;
        }

        @Test
        void apply_whenFunctionNotGranted_removesIt() {
            ExposureFilter.Result result = ExposureFilter.apply(withFunction("SETOF orders"),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getStoredProcedures()).isEmpty();
        }

        @Test
        void apply_whenFunctionGrantedAndReturnTableGranted_keepsIt() {
            ExposureFilter.Result result = ExposureFilter.apply(withFunction("SETOF orders"),
                    enforcing(grant("public.orders", Set.of(Operation.SELECT)),
                            grant("public.recent_orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getStoredProcedures()).containsKey("public.recent_orders");
        }

        @Test
        void apply_whenFunctionGrantedButReturnTableNotGranted_removesIt() {
            ExposureFilter.Result result = ExposureFilter.apply(withFunction("SETOF secrets"),
                    enforcing(grant("public.recent_orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getStoredProcedures()).isEmpty();
        }

        @Test
        void apply_whenFunctionReturnsAScalar_needsOnlyItsOwnGrant() {
            ExposureFilter.Result result = ExposureFilter.apply(withFunction("integer"),
                    enforcing(grant("public.recent_orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getStoredProcedures()).containsKey("public.recent_orders");
        }

        @Test
        void apply_whenFunctionReturnTypeIsUnknown_needsOnlyItsOwnGrant() {
            ExposureFilter.Result result = ExposureFilter.apply(withFunction(null),
                    enforcing(grant("public.recent_orders", Set.of(Operation.SELECT))), ROLE);

            assertThat(result.schemaInfo().getStoredProcedures()).containsKey("public.recent_orders");
        }
    }
}
