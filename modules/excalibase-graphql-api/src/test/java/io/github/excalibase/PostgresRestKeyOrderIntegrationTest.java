package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The same on Postgres: jsonb sorts keys by length, so the table's order is the engine's to restore. (EXC-836) */
class PostgresRestKeyOrderIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-key-order.sql");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";
    private static final int REPEATS = 25;
    private static final List<String> ZEBRA = List.of("id", "zulu", "alpha_long_name", "mid", "b");
    private static final List<String> STRIPE = List.of("id", "zebra_id", "whiskey", "a", "long_column_name");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    private static List<String> keys(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode data(String path) throws Exception {
        HttpAnswer answer = http.get(REST + path);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return MAPPER.readTree(answer.body()).get("data");
    }

    @Test
    void selectStarAndNoSelect_keepTableOrder_onEveryRepeat() throws Exception {
        for (String path : List.of("zebra", "zebra?select=*")) {
            for (int i = 0; i < REPEATS; i++) {
                assertThat(keys(data(path).get(0))).as(path + " #" + i).isEqualTo(ZEBRA);
            }
        }
    }

    @Test
    void anEmbed_keepsTableOrder_inTheParentAndInTheEmbeddedRows() throws Exception {
        for (int i = 0; i < REPEATS; i++) {
            JsonNode zebra = data("zebra?select=*,stripe(*)").get(0);
            List<String> expected = new ArrayList<>(ZEBRA);
            expected.add("stripe");
            assertThat(keys(zebra)).as("parent #" + i).isEqualTo(expected);
            assertThat(keys(zebra.get("stripe").get(0))).as("embedded #" + i).isEqualTo(STRIPE);
            JsonNode stripe = data("stripe?select=*,zebra(*)").get(0);
            List<String> child = new ArrayList<>(STRIPE);
            child.add("zebra");
            assertThat(keys(stripe)).as("child #" + i).isEqualTo(child);
            assertThat(keys(stripe.get("zebra"))).as("forward embed #" + i).isEqualTo(ZEBRA);
        }
    }
}
