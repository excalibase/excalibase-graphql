package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A row's keys come back in the table's column order, every time and wherever the row sits: MySQL's JSON type
 * sorts the keys of an object, so the order is the engine's to restore. Compares lists of names, never maps. (EXC-836)
 */
class MysqlRestKeyOrderIntegrationTest extends EngineIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql.sql");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";
    private static final int REPEATS = 25;
    private static final List<String> CUSTOMER = List.of("customer_id", "first_name", "last_name", "email", "active");
    private static final List<String> ORDER = List.of("order_id", "customer_id", "total_amount", "order_date");

    @Override
    public Map<String, String> getProperties() {
        mysql.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.database-type", "mysql");
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
    void selectStar_keepsTableOrder_onEveryRepeat() throws Exception {
        for (String select : List.of("customer", "customer?select=*", "customer?select=*&limit=3",
                "customer?select=*&order=customer_id.asc")) {
            for (int i = 0; i < REPEATS; i++) {
                assertThat(keys(data(select).get(0))).as(select + " #" + i).isEqualTo(CUSTOMER);
            }
        }
    }

    @Test
    void anEmbed_keepsTableOrder_inTheParentAndInTheEmbeddedRows() throws Exception {
        for (int i = 0; i < REPEATS; i++) {
            JsonNode customer = data("customer?select=*,orders(*)&order=customer_id.asc").get(0);
            assertThat(keys(customer)).as("parent #" + i).isEqualTo(append(CUSTOMER, "orders"));
            JsonNode orders = customer.get("orders");
            if (orders.size() > 0) {
                assertThat(keys(orders.get(0))).as("embedded #" + i).isEqualTo(ORDER);
            }
            JsonNode order = data("orders?select=*,customer(*)&order=order_id.asc").get(0);
            assertThat(keys(order)).as("child #" + i).isEqualTo(append(ORDER, "customer"));
            assertThat(keys(order.get("customer"))).as("forward embed #" + i).isEqualTo(CUSTOMER);
        }
    }

    @Test
    void aWrittenRow_comesBackInTableOrder() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpAnswer created = http.send("POST", REST + "customer", "{\"email\":\"e" + i + "@x\",\"last_name\":\"l\","
                    + "\"first_name\":\"f\"}", "Prefer", "return=representation", "Content-Type", "application/json");
            assertThat(created.status()).as(created.body()).isLessThan(300);
            JsonNode written = MAPPER.readTree(created.body()).get("data");
            assertThat(keys(written.isArray() ? written.get(0) : written)).as("created #" + i).isEqualTo(CUSTOMER);
        }
    }

    private static List<String> append(List<String> names, String extra) {
        List<String> all = new ArrayList<>(names);
        all.add(extra);
        return all;
    }
}
