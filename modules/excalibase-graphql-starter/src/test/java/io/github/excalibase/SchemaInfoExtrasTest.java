package io.github.excalibase;

import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.SchemaInfo.CompositeTypeField;
import io.github.excalibase.schema.SchemaInfo.FkInfo;
import io.github.excalibase.schema.SchemaInfo.ProcParam;
import io.github.excalibase.schema.SchemaInfo.ProcedureInfo;
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
    @DisplayName("addStoredProcedure registers a procedure retrievable by name")
    void addStoredProcedure_registersProcedure() {
        SchemaInfo schema = new SchemaInfo();
        ProcedureInfo info = new ProcedureInfo("pay_rental",
                List.of(new ProcParam("IN", "rental_id", "int4"),
                        new ProcParam("OUT", "paid", "boolean")));
        schema.addStoredProcedure("pay_rental", info);

        assertThat(schema.getStoredProcedures()).containsKey("pay_rental");
        assertThat(schema.getStoredProcedures().get("pay_rental")).isSameAs(info);
    }

    @Test
    @DisplayName("ProcedureInfo.inParams filters IN and INOUT params")
    void procedureInfo_inParams_returnsInAndInout() {
        ProcedureInfo info = new ProcedureInfo("foo",
                List.of(new ProcParam("IN", "a", "int"),
                        new ProcParam("OUT", "b", "text"),
                        new ProcParam("INOUT", "c", "text")));

        assertThat(info.inParams()).extracting(ProcParam::name).containsExactly("a", "c");
    }

    @Test
    @DisplayName("ProcedureInfo.outParams filters OUT and INOUT params")
    void procedureInfo_outParams_returnsOutAndInout() {
        ProcedureInfo info = new ProcedureInfo("foo",
                List.of(new ProcParam("IN", "a", "int"),
                        new ProcParam("OUT", "b", "text"),
                        new ProcParam("INOUT", "c", "text")));

        assertThat(info.outParams()).extracting(ProcParam::name).containsExactly("b", "c");
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
    @DisplayName("getStoredProcedures and getCompositeTypes return unmodifiable maps")
    void publicGetters_returnUnmodifiableMaps() {
        SchemaInfo schema = new SchemaInfo();
        schema.addStoredProcedure("p", new ProcedureInfo("p", List.of()));
        schema.addCompositeTypeField("t", "f", "int");

        var procs = schema.getStoredProcedures();
        var comps = schema.getCompositeTypes();
        var extraProc = new ProcedureInfo("x", List.of());
        var emptyFields = List.<SchemaInfo.CompositeTypeField>of();

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> procs.put("x", extraProc));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> comps.put("x", emptyFields));
    }
}
