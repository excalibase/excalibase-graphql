package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresQueryArgumentsIntegrationTest extends QueryArgumentsContract {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-query-arguments.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.postgres(postgres, "test-proj");
    }

    @Override
    String field() {
        return "publicProducts";
    }

    @Test
    void distinctOn_fromAVariable_keepsOneRowPerValue() throws Exception {
        assertThat(ids("query ($d: [String]) { publicProducts(distinctOn: $d, orderBy: { category: ASC, id: DESC })"
                + " { id } }", Map.of("d", List.of("category")))).containsExactly(5, 4);
    }
}
