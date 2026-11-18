package io.github.excalibase.admin;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSchemaViewTest {

    private static SchemaInfo reflected() {
        SchemaInfo info = new SchemaInfo();
        table(info, "public.orders", "public", "id", "int4", "total", "numeric");
        table(info, "public.t", "public", "id", "int4");
        table(info, "excalibase_meta.t", "excalibase_meta", "id", "int4", "secret", "text");
        table(info, "auth.users", "auth", "id", "int4", "password_hash", "text");
        table(info, "excalibase.jobs", "excalibase", "id", "int4");
        table(info, "internal.audit", "internal", "id", "int4");
        info.addPrimaryKey("public.orders", "id");
        info.addView("public.t");
        info.addFunction("public.plain_items", function("public", "plain_items", "public.orders"));
        info.addFunction("public.leak_meta", function("public", "leak_meta", "excalibase_meta.t"));
        info.addFunction("public.meta_label", new SchemaInfo.FunctionInfo("public", "meta_label",
                SchemaInfo.RoutineKind.FUNCTION, SchemaInfo.Volatility.STABLE, false, false, null,
                List.of(new SchemaInfo.FunctionArg("row_", "excalibase_meta.t", "i", false)), 1));
        info.addFunction("auth.read_users", function("auth", "read_users", "auth.users"));
        info.addFunction("excalibase_meta.read_t", function("excalibase_meta", "read_t", "excalibase_meta.t"));
        return info;
    }

    private static void table(SchemaInfo info, String key, String schema, String... columnsAndTypes) {
        info.setTableSchema(key, schema);
        for (int i = 0; i < columnsAndTypes.length; i += 2) {
            info.addColumn(key, columnsAndTypes[i], columnsAndTypes[i + 1]);
        }
    }

    private static SchemaInfo.FunctionInfo function(String schema, String name, String returnTable) {
        return new SchemaInfo.FunctionInfo(schema, name, SchemaInfo.RoutineKind.FUNCTION, SchemaInfo.Volatility.STABLE,
                false, true, returnTable, List.of(new SchemaInfo.FunctionArg("limit_", "int4", "i", false)), 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> view, String key) {
        return (List<Map<String, Object>>) view.get(key);
    }

    @Test
    void listsTheTablesWithTheirColumnsAndKeys() {
        Map<String, Object> view = AdminSchemaView.of(reflected(), ReservedSchemas.fromConfig(null));

        Map<String, Object> orders = list(view, "tables").stream()
                .filter(table -> "orders".equals(table.get("name"))).findFirst().orElseThrow();
        assertThat(orders).containsEntry("schema", "public").containsEntry("kind", "table");
        assertThat(list(orders, "columns")).extracting(column -> column.get("name")).containsExactly("id", "total");
        assertThat(list(orders, "columns").getFirst()).containsEntry("type", "int4").containsEntry("primaryKey", true);
        assertThat(list(orders, "columns").get(1)).containsEntry("primaryKey", false);
    }

    @Test
    void marksViews() {
        Map<String, Object> view = AdminSchemaView.of(reflected(), ReservedSchemas.fromConfig(null));

        assertThat(list(view, "tables")).anySatisfy(table -> assertThat(table)
                .containsEntry("name", "t").containsEntry("kind", "view"));
    }

    @Test
    void neverListsATableOfAReservedSchemaEvenIfReflectionHandedItOver() {
        Map<String, Object> view = AdminSchemaView.of(reflected(), ReservedSchemas.fromConfig(null));

        assertThat(list(view, "tables")).extracting(table -> table.get("schema"))
                .doesNotContain("excalibase_meta", "auth", "excalibase");
        assertThat(view.toString()).doesNotContain("password_hash").doesNotContain("secret");
    }

    @Test
    void neverListsAFunctionOfAReservedSchemaOrOneReturningOrTakingARowOfAReservedTable() {
        Map<String, Object> view = AdminSchemaView.of(reflected(), ReservedSchemas.fromConfig(null));

        assertThat(list(view, "functions")).extracting(function -> function.get("name")).containsExactly("plain_items");
    }

    @Test
    void aSchemaTheDeploymentReservesIsHiddenToo() {
        Map<String, Object> view = AdminSchemaView.of(reflected(), ReservedSchemas.fromConfig("internal"));

        assertThat(list(view, "tables")).extracting(table -> table.get("schema")).doesNotContain("internal");
    }

    @Test
    void anEmptySchemaIsAnEmptyView() {
        Map<String, Object> view = AdminSchemaView.of(new SchemaInfo(), ReservedSchemas.fromConfig(null));

        assertThat(list(view, "tables")).isEmpty();
        assertThat(list(view, "functions")).isEmpty();
    }
}
