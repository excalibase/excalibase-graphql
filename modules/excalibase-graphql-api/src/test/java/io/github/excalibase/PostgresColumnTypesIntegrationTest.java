package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ColumnTypesContract} on Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PostgresColumnTypesIntegrationTest extends ColumnTypesContract {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-column-types.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
    }

    @Override
    String ledgerField() {
        return "publicLedger";
    }

    @Override
    String maxBigId() {
        return "9223372036854775807";
    }

    @Test
    @DisplayName("arrays of bigints and decimals read as arrays of strings, through REST and GraphQL")
    void exactNumberArraysAreStrings() throws Exception {
        JsonNode row = rest("tallies?id=eq.1").get(0);
        assertThat(row.get("counts").toString()).isEqualTo("[\"9223372036854775807\",\"2\"]");
        assertThat(row.get("prices").toString()).isEqualTo("[\"1.50\",\"2.25\"]");

        JsonNode tally = graphql("{ publicTallies { counts prices } }", Map.of()).get("publicTallies").get(0);
        assertThat(tally.get("counts").toString()).isEqualTo("[\"9223372036854775807\",\"2\"]");
        assertThat(tally.get("prices").toString()).isEqualTo("[\"1.50\",\"2.25\"]");
    }

    @Test
    @DisplayName("a row wider than 50 columns reads whole, its decimal as a string")
    void wideRowsReadWhole() throws Exception {
        JsonNode row = rest("wide?id=eq.1").get(0);
        assertThat(row.get("total").asText()).isEqualTo("10.50");
        assertThat(row.get("c60").asInt()).isEqualTo(60);

        JsonNode wide = graphql("{ publicWide { total c1 c2 c3 c4 c5 c6 c7 c8 c9 c10 c11 c12 c13 c14 c15 c16 c17 c18 c19 "
                + "c20 c21 c22 c23 c24 c25 c26 c27 c28 c29 c30 c31 c32 c33 c34 c35 c36 c37 c38 c39 c40 c41 c42 c43 c44 "
                + "c45 c46 c47 c48 c49 c50 c51 c52 c53 c54 c55 c56 c57 c58 c59 c60 } }", Map.of()).get("publicWide").get(0);
        assertThat(wide.get("total").asText()).isEqualTo("10.50");
        assertThat(wide.get("c60").asInt()).isEqualTo(60);
    }
}
