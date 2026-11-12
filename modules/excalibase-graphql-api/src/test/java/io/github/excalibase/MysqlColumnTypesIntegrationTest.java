package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ColumnTypesContract} on MySQL, plus the types only MySQL has: TIME past 24 hours, BIT. */
class MysqlColumnTypesIntegrationTest extends ColumnTypesContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-column-types.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    @Override
    String ledgerField() {
        return "testLedger";
    }

    @Override
    String maxBigId() {
        return "18446744073709551615";
    }

    @Test
    @DisplayName("a TIME past 24 hours reads as it is stored, and is written from that form")
    void timeBeyondADay() throws Exception {
        assertThat(rest("ledger?select=span&id=eq.1").get(0).get("span").asText()).isEqualTo("838:59:59");
        assertThat(graphqlRow(1, "span").get("span").asText()).isEqualTo("838:59:59");

        restPost("ledger", "{\"id\": 60, \"span\": \"-100:30:00\"}");
        assertThat(rest("ledger?select=span&id=eq.60").get(0).get("span").asText()).isEqualTo("-100:30:00");
    }

    @Test
    @DisplayName("BIT reads as \\x hex of its bytes")
    void bitIsHex() throws Exception {
        assertThat(rest("ledger?select=flags&id=eq.1").get(0).get("flags").asText()).isEqualTo("\\xa5");
        assertThat(graphqlRow(1, "flags").get("flags").asText()).isEqualTo("\\xa5");

        graphql("mutation { create" + mutationSuffix() + "(input: { id: 61, flags: \"\\\\x0f\" }) { id } }", Map.of());
        assertThat(rest("ledger?select=flags&id=eq.61").get(0).get("flags").asText()).isEqualTo("\\x0f");
    }
}
