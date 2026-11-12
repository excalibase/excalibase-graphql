package io.github.excalibase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ColumnTypesContract} on MySQL, plus the types only MySQL has: TIME past 24 hours, BIT. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlColumnTypesIntegrationTest extends ColumnTypesContract {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-column-types.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "mysql");
        registry.add("app.max-rows", () -> 30);
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
