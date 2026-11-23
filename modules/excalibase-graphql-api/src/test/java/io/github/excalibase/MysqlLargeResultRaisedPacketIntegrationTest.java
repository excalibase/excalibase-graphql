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
}
