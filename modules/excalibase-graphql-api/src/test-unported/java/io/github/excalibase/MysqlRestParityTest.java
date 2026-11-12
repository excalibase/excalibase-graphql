package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * REST and GraphQL on the same MySQL fixtures answer the same rows: every REST read is checked against the
 * GraphQL query that means the same, and every REST write against what GraphQL reads afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlRestParityTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.max-rows", () -> 30);
        registry.add("app.database-type", () -> "mysql");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";
    private static final String CUSTOMER_FIELDS = "customer_id first_name last_name email";

    @Autowired
    private MockMvc mockMvc;

    private JsonNode rest(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(REST + path)).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode graphql(String query, String field) throws Exception {
        MvcResult result = mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("query", query)))).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertNull(body.get("errors"), body.toString());
        return body.get("data").get(field);
    }

    private JsonNode write(String method, String path, Object body, String prefer) throws Exception {
        var request = switch (method) {
            case "POST" -> post(REST + path);
            case "PATCH" -> patch(REST + path);
            default -> delete(REST + path);
        };
        request.header("Prefer", prefer).contentType(MediaType.APPLICATION_JSON);
        if (body != null) request.content(MAPPER.writeValueAsString(body));
        MvcResult result = mockMvc.perform(request).andReturn();
        assertTrue(result.getResponse().getStatus() < 300, result.getResponse().getContentAsString());
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("data");
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
        MvcResult result = mockMvc.perform(get(REST + "customer?select=customer_id&active=eq.true&limit=1")
                .header("Prefer", "count=exact")).andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        JsonNode aggregate = graphql("{ testCustomerAggregate(where: { active: { eq: true } }) { count } }",
                "testCustomerAggregate");
        assertEquals(aggregate.get("count").asLong(), body.get("pagination").get("total").asLong());
        assertTrue(result.getResponse().getHeader("Content-Range").endsWith("/" + aggregate.get("count").asLong()));
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
        MvcResult result = mockMvc.perform(post(REST + "member").contentType(MediaType.APPLICATION_JSON)
                .header("Prefer", "return=representation, resolution=merge-duplicates")
                .content(MAPPER.writeValueAsString(Map.of("id", 2, "email", "a@example.com", "name", "Stolen"))))
                .andReturn();
        assertEquals(409, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("unique_violation",
                MAPPER.readTree(result.getResponse().getContentAsString()).get("code").asText());
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
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertEquals(400, result.getResponse().getStatus(), path);
            assertTrue(result.getResponse().getContentAsString().contains("frist_name"), result.getResponse().getContentAsString());
        }
    }

    @Test
    @Order(20)
    void refusesWhatMysqlCannotDo() throws Exception {
        for (String path : new String[] {REST + "customer?email=wfts.alice", REST + "customer?email=sl.x",
                REST + "rpc/anything"}) {
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertEquals(400, result.getResponse().getStatus(), path);
            assertTrue(result.getResponse().getContentAsString().contains("MySQL"), result.getResponse().getContentAsString());
        }
    }
}
