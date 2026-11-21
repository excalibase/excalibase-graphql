package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GraphQL over a SQLite file: reads, filters, relationships, paging and writes, answering what Postgres answers. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqliteGraphqlIntegrationTest extends EngineIntegrationTest {

    private static final SqliteFile DATABASE = SqliteFile.create(SqliteFile.SHOP);

    @Override
    public Map<String, String> getProperties() {
        return DATABASE.engine("shop");
    }

    private JsonNode data(String query) {
        HttpAnswer answer = http.graphql("/shop/graphql", query);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        JsonNode body = answer.json();
        assertThat(body.path("errors").isMissingNode()).as(answer.body()).isTrue();
        return body.path("data");
    }

    private String error(String query) {
        JsonNode body = http.graphql("/shop/graphql", query).json();
        assertThat(body.path("errors").isMissingNode()).as(body.toString()).isFalse();
        return body.path("errors").get(0).path("message").asText();
    }

    @Test
    @Order(1)
    void list_filtersOrdersAndPages() {
        JsonNode rows = data("{ mainCustomers(where: { active: { eq: true } }, orderBy: { id: DESC }) { id name } }")
                .path("mainCustomers");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("name").asText()).isEqualTo("carol");
        assertThat(data("{ mainCustomers(orderBy: { id: ASC }, limit: 1, offset: 1) { id } }").path("mainCustomers").get(0)
                .path("id").asInt()).isEqualTo(2);
    }

    @Test
    @Order(2)
    void columnTypes_answerAsTheApiDoes() {
        JsonNode alice = data("{ mainCustomers(where: { id: { eq: 1 } }) { id active balance created_at joined meta visits } }")
                .path("mainCustomers").get(0);

        assertThat(alice.path("active").isBoolean()).isTrue();
        assertThat(alice.path("active").asBoolean()).isTrue();
        assertThat(alice.path("balance").asText()).isEqualTo("10.5");
        assertThat(alice.path("meta").path("vip").asBoolean()).isTrue();
        assertThat(alice.path("visits").asText()).isEqualTo("5");
        assertThat(data("{ mainCustomers(where: { id: { eq: 2 } }) { visits } }").path("mainCustomers").get(0)
                .path("visits").asText()).isEqualTo("9007199254740993");
    }

    @Test
    @Order(3)
    void relationships_forwardAndReverse() {
        JsonNode orders = data("{ mainOrders(orderBy: { id: ASC }) { id mainCustomer { name } } }").path("mainOrders");
        JsonNode alice = data("{ mainCustomers(where: { id: { eq: 1 } }) { name mainOrders(orderBy: { id: DESC }) { id total } } }")
                .path("mainCustomers").get(0);

        assertThat(orders.get(2).path("mainCustomer").path("name").asText()).isEqualTo("Bob");
        assertThat(alice.path("mainOrders")).hasSize(2);
        assertThat(alice.path("mainOrders").get(0).path("id").asInt()).isEqualTo(2);
    }

    @Test
    @Order(4)
    void filters_likeIlikeInIsNull() {
        assertThat(data("{ mainCustomers(where: { name: { like: \"A%\" } }) { id } }").path("mainCustomers")).hasSize(1);
        assertThat(data("{ mainCustomers(where: { name: { like: \"a%\" } }) { id } }").path("mainCustomers")).isEmpty();
        assertThat(data("{ mainCustomers(where: { name: { ilike: \"a%\" } }) { id } }").path("mainCustomers")).hasSize(1);
        assertThat(data("{ mainCustomers(where: { id: { in: [1, 3] } }) { id } }").path("mainCustomers")).hasSize(2);
        assertThat(data("{ mainCustomers(where: { email: { isNull: true } }) { id } }").path("mainCustomers")).hasSize(1);
        assertThat(data("{ mainOrders(where: { note: { contains: \"%\" } }) { id } }").path("mainOrders")).hasSize(1);
    }

    @Test
    @Order(5)
    void connection_pagesByCursor() {
        JsonNode first = data("{ mainCustomersConnection(first: 2, orderBy: { id: ASC }) { edges { cursor node { id } }"
                + " pageInfo { hasNextPage endCursor } totalCount } }").path("mainCustomersConnection");
        String after = first.path("pageInfo").path("endCursor").asText();
        JsonNode second = data("{ mainCustomersConnection(first: 2, after: \"" + after + "\", orderBy: { id: ASC }) {"
                + " edges { node { id } } pageInfo { hasNextPage } } }").path("mainCustomersConnection");

        assertThat(first.path("totalCount").asInt()).isEqualTo(3);
        assertThat(first.path("pageInfo").path("hasNextPage").asBoolean()).isTrue();
        assertThat(second.path("edges")).hasSize(1);
        assertThat(second.path("edges").get(0).path("node").path("id").asInt()).isEqualTo(3);
    }

    @Test
    @Order(6)
    void aggregate_counts() {
        assertThat(data("{ mainCustomersAggregate { count } }").path("mainCustomersAggregate").path("count").asInt()).isEqualTo(3);
    }

    @Test
    @Order(10)
    void create_returnsTheRowWithItsGeneratedKey() {
        JsonNode created = data("mutation { createMainCustomers(input: { name: \"Dan\", email: \"dan@x.io\","
                + " created_at: \"2026-03-01T12:30:00+02:00\", joined: \"2026-03-01\" }) { id name active created_at joined } }")
                .path("createMainCustomers");

        assertThat(created.path("id").asInt()).isGreaterThan(3);
        assertThat(created.path("active").asBoolean()).isTrue();
        assertThat(created.path("created_at").asText()).isEqualTo("2026-03-01T10:30:00.000000Z");
        assertThat(created.path("joined").asText()).isEqualTo("2026-03-01");
    }

    @Test
    @Order(11)
    void createMany_updateAndDelete_returnTheirRows() {
        JsonNode many = data("mutation { createManyMainCustomers(inputs: [{ name: \"E1\" }, { name: \"E2\" }]) { id name } }")
                .path("createManyMainCustomers");
        JsonNode updated = data("mutation { updateMainCustomers(where: { name: { eq: \"E1\" } }, input: { email: \"e1@x.io\" })"
                + " { id email } }").path("updateMainCustomers");
        JsonNode deleted = data("mutation { deleteMainCustomers(where: { name: { in: [\"E1\", \"E2\"] } }) { name } }")
                .path("deleteMainCustomers");

        assertThat(many).hasSize(2);
        assertThat(updated.get(0).path("email").asText()).isEqualTo("e1@x.io");
        assertThat(deleted).hasSize(2);
        assertThat(data("{ mainCustomers(where: { name: { eq: \"E2\" } }) { id } }").path("mainCustomers")).isEmpty();
    }

    @Test
    @Order(12)
    void compositeKeyTable_writesAndReadsBack() {
        JsonNode created = data("mutation { createMainTags(input: { order_id: 3, tag: \"z\" }) { order_id tag } }").path("createMainTags");
        JsonNode updated = data("mutation { updateMainTags(where: { tag: { eq: \"z\" } }, input: { tag: \"zz\" }) { order_id tag } }")
                .path("updateMainTags");

        assertThat(created.path("tag").asText()).isEqualTo("z");
        assertThat(updated.get(0).path("tag").asText()).isEqualTo("zz");
    }

    @Test
    @Order(13)
    void constraintViolations_areClientErrors() {
        assertThat(error("mutation { createMainCustomers(input: { name: \"X\", email: \"alice@x.io\" }) { id } }"))
                .containsIgnoringCase("unique");
        assertThat(error("mutation { createMainOrders(input: { customer_id: 999 }) { id } }")).containsIgnoringCase("foreign key");
        assertThat(error("mutation { createMainCustomers(input: { email: \"n@x.io\" }) { id } }")).containsIgnoringCase("name");
    }

    @Test
    @Order(14)
    void onConflict_insertsThenOverwritesTheNamedColumns() {
        String upsert = "mutation { createMainCustomers(input: { id: 70, name: \"%s\", email: \"u70@x.io\" },"
                + " onConflict: { constraint: \"PRIMARY\", update_columns: [name] }) { id name } }";

        assertThat(data(upsert.formatted("first")).path("createMainCustomers").path("name").asText()).isEqualTo("first");
        assertThat(data(upsert.formatted("second")).path("createMainCustomers").path("name").asText()).isEqualTo("second");
        assertThat(data("{ mainCustomers(where: { id: { eq: 70 } }) { name } }").path("mainCustomers")).hasSize(1);
    }

    @Test
    @Order(15)
    void onConflict_onAUniqueIndex_updatesTheRowHoldingIt_andRefusesAnUnknownConstraint() {
        data("mutation { createMainCustomers(input: { id: 71, name: \"keep\", email: \"u71@x.io\" }) { id } }");

        String index = "sqlite_autoindex_customers_1";
        data("mutation { createMainCustomers(input: { id: 72, name: \"merged\", email: \"u71@x.io\" },"
                + " onConflict: { constraint: \"" + index + "\", update_columns: [name] }) { id } }");

        assertThat(data("{ mainCustomers(where: { id: { eq: 71 } }) { name } }").path("mainCustomers").get(0)
                .path("name").asText()).isEqualTo("merged");
        assertThat(data("{ mainCustomers(where: { id: { eq: 72 } }) { id } }").path("mainCustomers")).isEmpty();
        assertThat(error("mutation { createMainCustomers(input: { id: 73, name: \"x\" },"
                + " onConflict: { constraint: \"nope\", update_columns: [name] }) { id } }")).contains("Unknown constraint");
    }

    @Test
    @Order(20)
    void unsupportedOperators_areNotInTheSchema() {
        assertThat(error("{ mainCustomers(where: { name: { regex: \"a\" } }) { id } }")).contains("regex");
        assertThat(error("{ mainCustomers(where: { meta: { contains: \"a\" } }) { id } }")).contains("contains");
    }
}
