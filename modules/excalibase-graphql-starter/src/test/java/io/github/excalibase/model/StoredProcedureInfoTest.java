package io.github.excalibase.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoredProcedureInfoTest {

    @Test
    void shouldCreateStoredProcedureInfoWithName() {
        StoredProcedureInfo proc = new StoredProcedureInfo();
        proc.setName("get_customer_order_count");
        proc.setSchema("testdb");
        proc.setParameters(List.of());

        assertThat(proc.getName()).isEqualTo("get_customer_order_count");
        assertThat(proc.getSchema()).isEqualTo("testdb");
        assertThat(proc.getParameters()).isEmpty();
    }

    @Test
    void shouldCreateProcedureParamWithAllFields() {
        StoredProcedureInfo.ProcedureParam param = new StoredProcedureInfo.ProcedureParam();
        param.setName("p_customer_id");
        param.setDataType("int");
        param.setMode("IN");
        param.setPosition(1);

        assertThat(param.getName()).isEqualTo("p_customer_id");
        assertThat(param.getDataType()).isEqualTo("int");
        assertThat(param.getMode()).isEqualTo("IN");
        assertThat(param.getPosition()).isEqualTo(1);
    }

    @Test
    void shouldDistinguishInAndOutParams() {
        StoredProcedureInfo.ProcedureParam inParam = new StoredProcedureInfo.ProcedureParam("p_customer_id", "int", "IN", 1);
        StoredProcedureInfo.ProcedureParam outParam = new StoredProcedureInfo.ProcedureParam("p_count", "bigint", "OUT", 2);

        StoredProcedureInfo proc = new StoredProcedureInfo("get_customer_order_count", "testdb",
                List.of(inParam, outParam));

        List<StoredProcedureInfo.ProcedureParam> inParams = proc.getParameters().stream()
                .filter(p -> "IN".equals(p.getMode()))
                .toList();
        List<StoredProcedureInfo.ProcedureParam> outParams = proc.getParameters().stream()
                .filter(p -> "OUT".equals(p.getMode()))
                .toList();

        assertThat(inParams).hasSize(1);
        assertThat(inParams.getFirst().getName()).isEqualTo("p_customer_id");
        assertThat(outParams).hasSize(1);
        assertThat(outParams.getFirst().getName()).isEqualTo("p_count");
    }
}
