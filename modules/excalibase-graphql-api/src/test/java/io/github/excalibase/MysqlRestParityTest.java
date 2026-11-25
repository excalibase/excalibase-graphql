package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.excalibase.support.Expect.status;

/**
 * REST and GraphQL on the same MySQL fixtures answer the same rows: every REST read is checked against the
 * GraphQL query that means the same, and every REST write against what GraphQL reads afterwards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlRestParityTest extends EngineIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql.sql");

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
        properties.put("app.max-rows", "30");
        properties.put("app.database-type", "mysql");
        return properties;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";
    private static final String CUSTOMER_FIELDS = "customer_id first_name last_name email";

    private JsonNode rest(String path) throws Exception {
        HttpAnswer result = http.get(REST + path);
        assertEquals(200, result.status(), result.body());
        return MAPPER.readTree(result.body()).get("data");
    }

    private JsonNode graphql(String query, String field) throws Exception {
        HttpAnswer result = http.post("/test-proj/graphql", MAPPER.writeValueAsString(Map.of("query", query)), "Content-Type", "application/json");
        JsonNode body = MAPPER.readTree(result.body());
        assertNull(body.get("errors"), body.toString());
        return body.get("data").get(field);
    }

    private JsonNode write(String method, String path, Object body, String prefer) throws Exception {
        String payload = body == null ? null : MAPPER.writeValueAsString(body);
        HttpAnswer result = http.send(method, REST + path, payload, "Prefer", prefer, "Content-Type", "application/json");
        assertTrue(result.status() < 300, result.body());
        return MAPPER.readTree(result.body()).get("data");
    }

    /** The same JSON, numbers compared by value (100.5 and 100.50 are one amount). */
    private static void assertSameRows(JsonNode expected, JsonNode actual) {
        Comparator<JsonNode> byValue = (left, right) -> left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) : (left.equals(right) ? 0 : 1);
        assertTrue(expected.equals(byValue, actual), "GraphQL " + expected + "\nREST    " + actual);
    }

    private void assertParity(String restPath, String graphqlQuery, String field) throws Exception {
        JsonNode viaRest = rest(restPath);
        assertFalse(viaRest.isEmpty(), restPath + " answered no rows: the parity would be vacuous");
        assertSameRows(graphql(graphqlQuery, field), viaRest);
    }

    @Test
    @Order(1)
    void listsColumnsInOrder() throws Exception {
        assertParity("customer?select=customer_id,first_name,last_name,email&order=customer_id.asc",
                "{ testCustomer(orderBy: { customer_id: ASC }) { " + CUSTOMER_FIELDS + " } }", "testCustomer");
        assertParity("orders?select=order_id,customer_id,total_amount,order_date&order=total_amount.desc&limit=3&offset=1",
                "{ testOrders(orderBy: { total_amount: DESC }, limit: 3, offset: 1) { order_id customer_id total_amount order_date } }",
                "testOrders");
    }

    @Test
    @Order(2)
    void filtersMeanTheSame() throws Exception {
        String[][] cases = {
                {"customer_id=gt.2", "customer_id: { gt: 2 }"},
                {"customer_id=lte.3", "customer_id: { lte: 3 }"},
                {"first_name=neq.Alice", "first_name: { neq: \"Alice\" }"},
                {"customer_id=in.(1,3,5)", "customer_id: { in: [1, 3, 5] }"},
                {"customer_id=not.in.(1,3,5)", "customer_id: { notIn: [1, 3, 5] }"},
                {"last_name=like.*i*", "last_name: { like: \"%i%\" }"},
                {"first_name=ilike.ALI*", "first_name: { ilike: \"ali%\" }"},
                {"email=not.is.null", "email: { isNotNull: true }"},
                {"active=eq.true", "active: { eq: true }"},
        };
        for (String[] filter : cases) {
            assertParity("customer?select=customer_id,first_name&order=customer_id.asc&" + filter[0],
                    "{ testCustomer(orderBy: { customer_id: ASC }, where: { " + filter[1] + " }) { customer_id first_name } }",
                    "testCustomer");
        }
        assertParity("customer?select=customer_id&order=customer_id.asc&or=(first_name.eq.Alice,first_name.eq.Bob)",
                "{ testCustomer(orderBy: { customer_id: ASC }, where: { or: [{ first_name: { eq: \"Alice\" } },"
                        + " { first_name: { eq: \"Bob\" } }] }) { customer_id } }", "testCustomer");
    }

    @Test
    @Order(3)
    void embedsMatchRelationships() throws Exception {
        JsonNode viaRest = rest("orders?select=order_id,total_amount,customer(first_name,last_name)&order=order_id.asc");
        JsonNode viaGraphql = graphql("{ testOrders(orderBy: { order_id: ASC }) { order_id total_amount"
                + " testCustomerId { first_name last_name } } }", "testOrders");
        assertEquals(viaGraphql.size(), viaRest.size());
        for (int i = 0; i < viaRest.size(); i++) {
            assertSameRows(viaGraphql.get(i).get("order_id"), viaRest.get(i).get("order_id"));
            assertSameRows(viaGraphql.get(i).get("total_amount"), viaRest.get(i).get("total_amount"));
            assertSameRows(viaGraphql.get(i).get("testCustomerId"), viaRest.get(i).get("customer"));
        }

        JsonNode customers = rest("customer?select=customer_id,orders(order_id,total_amount)&order=customer_id.asc");
        JsonNode viaGraphqlCustomers = graphql("{ testCustomer(orderBy: { customer_id: ASC }) { customer_id"
                + " testOrders(orderBy: { order_id: ASC }) { order_id total_amount } } }", "testCustomer");
        assertEquals(viaGraphqlCustomers.size(), customers.size());
        for (int i = 0; i < customers.size(); i++) {
            assertSameRows(viaGraphqlCustomers.get(i).get("testOrders"), customers.get(i).get("orders"));
        }
    }

    @Test
    @Order(4)
    void countsMatchTheAggregate() throws Exception {
        HttpAnswer result = http.get(REST + "customer?select=customer_id&active=eq.true&limit=1", "Prefer", "count=exact");
        JsonNode body = MAPPER.readTree(result.body());
        JsonNode aggregate = graphql("{ testCustomerAggregate(where: { active: { eq: true } }) { count } }",
                "testCustomerAggregate");
        assertEquals(aggregate.get("count").asLong(), body.get("pagination").get("total").asLong());
        assertTrue(result.header("Content-Range").endsWith("/" + aggregate.get("count").asLong()));
    }

    @Test
    @Order(10)
    void insertReturnsWhatGraphqlReads() throws Exception {
        JsonNode created = write("POST", "customer",
                Map.of("first_name", "Rest", "last_name", "Insert", "email", "rest@example.com"), "return=representation");
        int id = created.get("customer_id").asInt();
        assertTrue(id > 5);
        JsonNode viaGraphql = graphql("{ testCustomer(where: { customer_id: { eq: " + id + " } }) { "
                + CUSTOMER_FIELDS + " } }", "testCustomer");
        assertSameRows(viaGraphql.get(0), MAPPER.valueToTree(Map.of("customer_id", id, "first_name", "Rest",
                "last_name", "Insert", "email", "rest@example.com")));
        assertEquals("Rest", created.get("first_name").asText());

        JsonNode bulk = write("POST", "customer", List.of(Map.of("first_name", "Bulk", "last_name", "One"),
                Map.of("first_name", "Bulk", "last_name", "Two")), "return=representation");
        assertEquals(2, bulk.size());
        assertParity("customer?select=customer_id,first_name,last_name&first_name=eq.Bulk&order=customer_id.asc",
                "{ testCustomer(orderBy: { customer_id: ASC }, where: { first_name: { eq: \"Bulk\" } }) { customer_id first_name last_name } }",
                "testCustomer");
        assertEquals(bulk.get(0).get("customer_id"), rest("customer?select=customer_id&first_name=eq.Bulk&order=customer_id.asc")
                .get(0).get("customer_id"));
    }

    @Test
    @Order(11)
    void updateReturnsWhatGraphqlReads() throws Exception {
        JsonNode updated = write("PATCH", "customer?customer_id=eq.2", Map.of("email", "bob@rest.example"),
                "return=representation");
        assertEquals(1, updated.size());
        assertSameRows(graphql("{ testCustomer(where: { customer_id: { eq: 2 } }) { " + CUSTOMER_FIELDS + " } }",
                "testCustomer"), rest("customer?select=customer_id,first_name,last_name,email&customer_id=eq.2"));
        assertEquals("bob@rest.example", updated.get(0).get("email").asText());

        graphql("mutation { updateTestCustomer(where: { customer_id: { eq: 2 } }, input: { email: \"bob@graphql.example\" })"
                + " { customer_id } }", "updateTestCustomer");
        assertEquals("bob@graphql.example", rest("customer?select=email&customer_id=eq.2").get(0).get("email").asText());
    }

    @Test
    @Order(12)
    void upsertMergesDuplicates() throws Exception {
        JsonNode merged = write("POST", "order_items",
                Map.of("order_id", 1, "product_id", 1, "quantity", 9, "price", 25.25),
                "return=representation, resolution=merge-duplicates");
        assertEquals(9, merged.get("quantity").asInt());
        JsonNode inserted = write("POST", "order_items",
                Map.of("order_id", 5, "product_id", 7, "quantity", 1, "price", 3.5),
                "return=representation, resolution=merge-duplicates");
        assertEquals(7, inserted.get("product_id").asInt());
        assertParity("order_items?select=order_id,product_id,quantity,price&order=order_id.asc,product_id.asc",
                "{ testOrderItems(orderBy: [{ order_id: ASC }, { product_id: ASC }]) { order_id product_id quantity price } }",
                "testOrderItems");
    }

    @Test
    @Order(14)
    void upsertCollidingOnAnotherUniqueKeyIsAConflictLikePostgres() throws Exception {
        HttpAnswer result = http.post(REST + "member", MAPPER.writeValueAsString(Map.of("id", 2, "email", "a@example.com", "name", "Stolen")), "Content-Type", "application/json", "Prefer", "return=representation, resolution=merge-duplicates");
        assertEquals(409, result.status(), result.body());
        assertEquals("unique_violation",
                MAPPER.readTree(result.body()).get("code").asText());
        assertParity("member?select=id,email,name&order=id.asc",
                "{ testMember(orderBy: { id: ASC }) { id email name } }", "testMember");
        assertEquals("Ann", rest("member?select=name&id=eq.1").get(0).get("name").asText());
        assertEquals("b@example.com", rest("member?select=email&id=eq.2").get(0).get("email").asText());
    }

    @Test
    @Order(13)
    void deleteReturnsTheRowsGraphqlNoLongerSees() throws Exception {
        JsonNode deleted = write("DELETE", "customer?first_name=eq.Bulk", null, "return=representation");
        assertEquals(2, deleted.size());
        assertEquals(0, graphql("{ testCustomer(where: { first_name: { eq: \"Bulk\" } }) { customer_id } }",
                "testCustomer").size());
    }

    @Test
    @Order(21)
    void refusesAnUnknownFilterColumnInsteadOfReturningEveryRow() throws Exception {
        for (String path : new String[] {REST + "customer?frist_name=eq.Alice",
                REST + "customer?or=(customer_id.eq.1,frist_name.eq.Alice)"}) {
            HttpAnswer result = http.get(path);
            assertEquals(400, result.status(), path);
            assertTrue(result.body().contains("frist_name"), result.body());
        }
    }

    @Test
    @Order(20)
    void refusesWhatMysqlCannotDo() throws Exception {
        for (String path : new String[] {REST + "customer?email=wfts.alice", REST + "customer?email=sl.x",
                REST + "rpc/anything"}) {
            HttpAnswer result = http.get(path);
            assertEquals(400, result.status(), path);
            assertTrue(result.body().contains("MySQL"), result.body());
        }
    }

    private HttpAnswer postRows(String path, Object rows, String prefer) throws Exception {
        return http.post(REST + path, MAPPER.writeValueAsString(rows), "Content-Type", "application/json", "Prefer", prefer);
    }

    private String memberName(int id) throws Exception {
        return rest("member?select=name&id=eq." + id).get(0).get("name").asText();
    }

    @Test
    @Order(30)
    void bulkMergeDuplicatesUpdatesExistingRowsAndInsertsTheRest() throws Exception {
        HttpAnswer result = postRows("member", List.of(
                Map.of("id", 1, "email", "a@example.com", "name", "Anna"),
                Map.of("id", 3, "email", "c@example.com", "name", "Cy")), "return=representation, resolution=merge-duplicates");
        assertEquals(201, result.status(), result.body());
        JsonNode rows = MAPPER.readTree(result.body()).get("data");
        assertEquals(List.of(1, 3), List.of(rows.get(0).get("id").asInt(), rows.get(1).get("id").asInt()));
        assertEquals("Anna", rows.get(0).get("name").asText());
        assertParity("member?select=id,email,name&order=id.asc",
                "{ testMember(orderBy: { id: ASC }) { id email name } }", "testMember");
        assertEquals("Anna", memberName(1));
    }

    @Test
    @Order(31)
    void bulkIgnoreDuplicatesWritesAndAnswersOnlyTheNewRows() throws Exception {
        HttpAnswer result = postRows("member", List.of(
                Map.of("id", 1, "email", "a@example.com", "name", "Ignored"),
                Map.of("id", 4, "email", "d@example.com", "name", "Dee")), "return=representation, resolution=ignore-duplicates");
        assertEquals(201, result.status(), result.body());
        JsonNode rows = MAPPER.readTree(result.body()).get("data");
        assertEquals(1, rows.size());
        assertEquals(4, rows.get(0).get("id").asInt());
        assertEquals("Anna", memberName(1));
    }

    @Test
    @Order(32)
    void bulkMergeOnConflictColumnsFindsTheRowByThem() throws Exception {
        HttpAnswer result = postRows("member?on_conflict=email", List.of(
                Map.of("email", "b@example.com", "name", "Bea")), "return=representation, resolution=merge-duplicates");
        assertEquals(201, result.status(), result.body());
        assertEquals(2, MAPPER.readTree(result.body()).get("data").get(0).get("id").asInt());
        assertEquals("Bea", memberName(2));
    }

    @Test
    @Order(33)
    void bulkMergeWithoutRepresentationAnswers201AndNoBody() throws Exception {
        HttpAnswer result = postRows("member", List.of(Map.of("id", 4, "email", "d@example.com", "name", "Dede")),
                "resolution=merge-duplicates");
        assertEquals(201, result.status(), result.body());
        assertTrue(result.body() == null || result.body().isEmpty(), result.body());
        assertEquals("Dede", memberName(4));
    }

    @Test
    @Order(34)
    void bulkMergeBindsValuesAndRollsBackWhenARowFails() throws Exception {
        String hostile = "x'); DROP TABLE member;--";
        assertEquals(201, postRows("member", List.of(Map.of("id", 1, "email", "a@example.com", "name", hostile)),
                "return=representation, resolution=merge-duplicates").status());
        assertEquals(hostile, memberName(1));
        HttpAnswer failed = postRows("member", List.of(
                Map.of("id", 1, "email", "a@example.com", "name", "Changed"),
                Map.of("id", 3, "email", "a@example.com", "name", "Collides")), "return=representation, resolution=merge-duplicates");
        assertEquals(409, failed.status(), failed.body());
        assertEquals(hostile, memberName(1));
    }

    @Test
    @Order(35)
    void aPlainArrayWithADuplicateKeyIsStillAConflict() throws Exception {
        HttpAnswer result = postRows("member", List.of(Map.of("id", 1, "email", "z@example.com", "name", "Z")),
                "return=representation");
        assertEquals(409, result.status(), result.body());
    }
}
