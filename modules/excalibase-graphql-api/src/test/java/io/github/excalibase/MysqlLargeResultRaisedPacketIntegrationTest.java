package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** With room for it, the same large result is answered whole: the refusal is about the limit, not the size. (EXC-838) */
class MysqlLargeResultRaisedPacketIntegrationTest extends MysqlLargeResultIntegrationTest {

    @Override
    String packetBytes() {
        return "67108864";
    }

    @Test
    void aThousandWideRows_comeBackWhole() throws Exception {
        HttpAnswer answer = http.get("/test-proj/api/v1/wide?select=*&order=id.asc&limit=1000");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(answer.body()).get("data").size()).isEqualTo(1000);
    }

    /** The 1.4 MB row fits in 64 MB, so it is answered, not refused. */
    @Override
    @Test
    void aSingleRowTooBigForOneAnswer_isRefused_notAnEmptyList() throws Exception {
        HttpAnswer answer = http.get("/test-proj/api/v1/big?id=eq.1&select=id,tag");

        assertThat(answer.status()).isEqualTo(200);
        HttpAnswer whole = http.get("/test-proj/api/v1/big?id=eq.1");
        assertThat(whole.status()).as(whole.body().length() + " bytes").isEqualTo(200);
        assertThat(new ObjectMapper().readTree(whole.body()).get("data").get(0).get("a").asText()).hasSize(700000);
    }

    @Override
    @Test
    void aWriteWhoseRepresentationIsTooBig_isRefused_andNothingIsWritten() throws Exception {
        HttpAnswer answer = http.patchJson("/test-proj/api/v1/big?id=eq.1", java.util.Map.of("tag", "u"),
                "Prefer", "return=representation");

        assertThat(answer.status()).as(answer.body().length() + " bytes").isEqualTo(200);
        assertThat(new ObjectMapper().readTree(answer.body()).get("data").get(0).get("tag").asText()).isEqualTo("u");
    }

    @Override
    @Test
    void aBigResultBuriedUnderWarnings_isRefused_asJson() throws Exception {
        HttpAnswer answer = http.get("/test-proj/api/v1/flood?select=*&order=id.asc&limit=1000");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(new ObjectMapper().readTree(answer.body()).get("data").size()).isEqualTo(1000);
    }

    @Override
    @Test
    void aBigResultBuriedUnderWarnings_isRefused_asCsv() throws Exception {
        HttpAnswer answer = http.get("/test-proj/api/v1/flood?select=*&order=id.asc&limit=1000", "Accept", "text/csv");

        assertThat(answer.status()).isEqualTo(200);
    }
}
