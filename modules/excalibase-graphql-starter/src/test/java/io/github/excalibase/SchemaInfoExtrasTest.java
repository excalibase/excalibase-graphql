package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.CompositeTypeField;
import io.github.excalibase.schema.SchemaInfo.FkInfo;
import io.github.excalibase.schema.SchemaInfo.FunctionArg;
import io.github.excalibase.schema.SchemaInfo.FunctionInfo;
import io.github.excalibase.schema.SchemaInfo.ReverseFkInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaInfoExtrasTest {

    @Test
    @DisplayName("getViewNames returns an unmodifiable snapshot of registered views")
    void getViewNames_returnsRegisteredViews() {
        SchemaInfo schema = new SchemaInfo();
        schema.addView("active_customers");
        schema.addView("orders_summary");

        assertThat(schema.getViewNames()).containsExactlyInAnyOrder("active_customers", "orders_summary");
    }

    @Test
    @DisplayName("addCompositeTypeField groups multiple fields under the same type")
    void addCompositeTypeField_groupsFields() {
        SchemaInfo schema = new SchemaInfo();
        schema.addCompositeTypeField("address", "street", "text");
        schema.addCompositeTypeField("address", "city", "text");
        schema.addCompositeTypeField("address", "zip", "varchar");

        assertThat(schema.getCompositeTypes()).containsKey("address");
        List<CompositeTypeField> fields = schema.getCompositeTypes().get("address");
        assertThat(fields).extracting(CompositeTypeField::name)
                .containsExactly("street", "city", "zip");
        assertThat(fields).extracting(CompositeTypeField::type)
                .containsExactly("text", "text", "varchar");
    }

    @Test
    @DisplayName("addFunction registers a reflected routine retrievable by name")
    void addFunction_registersRoutine() {
        SchemaInfo schema = new SchemaInfo();
        FunctionInfo info = new FunctionInfo("public", "pay_rental", SchemaInfo.RoutineKind.FUNCTION,
                SchemaInfo.Volatility.VOLATILE, false, true, "public.rental",
                List.of(new FunctionArg("rental_id", "integer", "i", false)), 1);
        schema.addFunction("public.pay_rental", info);

        assertThat(schema.getFunctions()).containsKey("public.pay_rental");
        assertThat(schema.getFunction("public.pay_rental")).isSameAs(info);
        assertThat(info.qualifiedName()).isEqualTo("public.pay_rental");
    }

    @Test
    @DisplayName("FunctionArg.isInput holds for in, inout and variadic arguments only")
    void functionArg_isInput() {
        assertThat(List.of("i", "b", "v", "o", "t"))
                .map(mode -> new FunctionArg("a", "int", mode, false).isInput())
                .containsExactly(true, true, true, false, false);
    }

    @Test
    @DisplayName("Volatility maps pg_proc.provolatile, anything else is unknown")
    void volatility_fromCatalog() {
        assertThat(SchemaInfo.Volatility.fromCatalog("i")).isEqualTo(SchemaInfo.Volatility.IMMUTABLE);
        assertThat(SchemaInfo.Volatility.fromCatalog("s")).isEqualTo(SchemaInfo.Volatility.STABLE);
        assertThat(SchemaInfo.Volatility.fromCatalog("v")).isEqualTo(SchemaInfo.Volatility.VOLATILE);
        assertThat(SchemaInfo.Volatility.fromCatalog("x")).isNull();
        assertThat(SchemaInfo.Volatility.fromCatalog(null)).isNull();
    }

    @Test
    @DisplayName("FkInfo convenience accessors return first col and detect composite keys")
    void fkInfo_accessors_workForSingleAndCompositeKeys() {
        FkInfo single = new FkInfo(List.of("user_id"), "users", List.of("id"));
        assertThat(single.fkColumn()).isEqualTo("user_id");
        assertThat(single.refColumn()).isEqualTo("id");
        assertThat(single.isComposite()).isFalse();

        FkInfo composite = new FkInfo(List.of("a", "b"), "parent", List.of("x", "y"));
        assertThat(composite.fkColumn()).isEqualTo("a");
        assertThat(composite.refColumn()).isEqualTo("x");
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    @DisplayName("ReverseFkInfo convenience accessors mirror FkInfo")
    void reverseFkInfo_accessors_workForSingleAndCompositeKeys() {
        ReverseFkInfo single = new ReverseFkInfo("orders", List.of("customer_id"), List.of("id"));
        assertThat(single.fkColumn()).isEqualTo("customer_id");
        assertThat(single.refColumn()).isEqualTo("id");
        assertThat(single.isComposite()).isFalse();

        ReverseFkInfo composite = new ReverseFkInfo("rentals", List.of("a", "b"), List.of("x", "y"));
        assertThat(composite.isComposite()).isTrue();
    }

    @Test
    @DisplayName("addExtension registers extension with version, hasExtension reports presence")
    void addExtension_registersExtension() {
        SchemaInfo schema = new SchemaInfo();
        schema.addExtension("pg_trgm", "1.6");
        schema.addExtension("vector", "0.7.0");

        assertThat(schema.hasExtension("pg_trgm")).isTrue();
        assertThat(schema.hasExtension("postgis")).isFalse();
        assertThat(schema.getExtensionVersion("pg_trgm")).isEqualTo("1.6");
        assertThat(schema.getExtensionVersion("missing")).isNull();
        assertThat(schema.getExtensions()).containsOnlyKeys("pg_trgm", "vector");
    }

    @Test
    @DisplayName("getFunctions and getCompositeTypes return unmodifiable maps")
    void publicGetters_returnUnmodifiableMaps() {
        SchemaInfo schema = new SchemaInfo();
        var function = new FunctionInfo("public", "p", SchemaInfo.RoutineKind.PROCEDURE, null, false, false, null,
                List.of(), 1);
        schema.addFunction("public.p", function);
        schema.addCompositeTypeField("t", "f", "int");

        var procs = schema.getFunctions();
        var comps = schema.getCompositeTypes();
        var extraProc = function;
        var emptyFields = List.<SchemaInfo.CompositeTypeField>of();

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> procs.put("x", extraProc));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> comps.put("x", emptyFields));
    }
}
