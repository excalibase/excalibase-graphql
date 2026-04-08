package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-mysql.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.schemas", mysql::getDatabaseName);
        registry.add("app.max-rows", () -> 30);
        registry.add("app.database-type", () -> "mysql");
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === List queries ===

    @Test
    @Order(1)
    void listCustomers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(orderBy: { customer_id: ASC }, limit: 5) { customer_id first_name last_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(5)))
                .andExpect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void listWithWhereFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { active: { eq: true } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(4)));
    }

    @Test
    @Order(3)
    void listWithLimit() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(limit: 2) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    // === Forward FK ===

    @Test
    @Order(5)
    void forwardFkRelationship() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrders(limit: 1, orderBy: { order_id: ASC }) { order_id total_amount testCustomerId { first_name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrders[0].testCustomerId.first_name").value("Alice"));
    }

    // === Reverse FK ===

    @Test
    @Order(6)
    void reverseFkRelationship() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { customer_id: { eq: 1 } }) { first_name testCustomerId { order_id } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer[0].testCustomerId", hasSize(2)));
    }

    // === Connection ===

    @Test
    @Order(10)
    void connectionBasic() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomerConnection(first: 2) { edges { cursor node { customer_id first_name } } pageInfo { hasNextPage } totalCount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomerConnection.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.testCustomerConnection.pageInfo.hasNextPage").value(true))
                .andExpect(jsonPath("$.data.testCustomerConnection.totalCount").value(5));
    }

    // === Aggregates ===

    @Test
    @Order(20)
    void aggregateCount() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomerAggregate { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomerAggregate.count").value(5));
    }

    // === Mutations ===

    @Test
    @Order(30)
    void createCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createTestCustomer(input: { first_name: \"Test\", last_name: \"MySQL\" }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTestCustomer.first_name").value("Test"))
                .andExpect(jsonPath("$.data.createTestCustomer.customer_id").isNumber());
    }

    @Test
    @Order(31)
    void updateCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { updateTestCustomer(where: { customer_id: { eq: 1 } }, input: { email: \"updated@mysql.com\" }) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTestCustomer[0].email").value("updated@mysql.com"));
    }

    @Test
    @Order(32)
    void deleteCustomer() throws Exception {
        // Create then capture the ID
        var createResult = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createTestCustomer(input: { first_name: \"ToDelete\", last_name: \"User\" }) { customer_id } }")))
                .andExpect(status().isOk())
                .andReturn();
        int createdId = com.jayway.jsonpath.JsonPath.read(
                createResult.getResponse().getContentAsString(), "$.data.createTestCustomer.customer_id");

        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { deleteTestCustomer(where: { customer_id: { eq: " + createdId + " } }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteTestCustomer[0].first_name").value("ToDelete"));
    }

    // === Bulk create (createMany) ===

    @Test
    @Order(33)
    void bulkCreateCustomers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createManyTestCustomer(inputs: [{ first_name: \"Bulk1\", last_name: \"Test\" }, { first_name: \"Bulk2\", last_name: \"Test\" }]) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createManyTestCustomer", hasSize(2)));
    }

    // === WHERE filters ===

    @Test
    @Order(40)
    void whereNeqFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { first_name: { neq: \"Alice\" } }, limit: 10) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(41)
    void whereGtFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrders(where: { total_amount: { gt: 100 } }) { order_id total_amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(42)
    void whereGteFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrders(where: { total_amount: { gte: 250 } }) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrders", hasSize(2)));
    }

    @Test
    @Order(43)
    void whereLtFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrders(where: { total_amount: { lt: 100 } }) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrders", hasSize(2)));
    }

    @Test
    @Order(44)
    void whereLteFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrders(where: { total_amount: { lte: 100.50 } }) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrders", hasSize(3)));
    }

    @Test
    @Order(45)
    void whereInFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { customer_id: { in: [1, 3] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    @Test
    @Order(46)
    void whereLikeFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { first_name: { like: \"A%\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(47)
    void whereIlikeFilter() throws Exception {
        // MySQL uses LOWER(col) LIKE LOWER(val) for ilike
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { first_name: { ilike: \"alice\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    // === ORDER BY ===

    @Test
    @Order(50)
    void orderByDesc() throws Exception {
        // Earlier mutations add customers — filter to original 5 to ensure stable ordering
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(orderBy: { first_name: DESC }, limit: 1, where: { customer_id: { lte: 5 } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer[0].first_name").value("Eve"));
    }

    // === Aggregates ===

    @Test
    @Order(60)
    void aggregateSumAvgMinMax() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrdersAggregate { count sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrdersAggregate.count").value(5))
                .andExpect(jsonPath("$.data.testOrdersAggregate.sum.total_amount").isNumber())
                .andExpect(jsonPath("$.data.testOrdersAggregate.max.total_amount").isNumber());
    }

    // === Connection with cursor pagination ===

    @Test
    @Order(70)
    void connectionAfterCursor() throws Exception {
        // Get first page
        var result = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomerConnection(first: 2) { edges { cursor node { customer_id } } } }")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(body, "$.data.testCustomerConnection.edges[1].cursor");

        // Get second page using after cursor
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomerConnection(first: 2, after: \"" + cursor + "\") { edges { node { customer_id first_name } } pageInfo { hasPreviousPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomerConnection.edges[0].node.customer_id").value(3))
                .andExpect(jsonPath("$.data.testCustomerConnection.pageInfo.hasPreviousPage").value(true));
    }

    // === Multiple root fields ===

    @Test
    @Order(80)
    void multipleRootFields() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(limit: 1, orderBy: { customer_id: ASC }) { first_name } testOrders(limit: 1, orderBy: { order_id: ASC }) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"))
                .andExpect(jsonPath("$.data.testOrders[0].order_id").value(1));
    }

    // === Error cases ===

    @Test
    @Order(90)
    void errorInvalidField() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ nonexistentTable { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // === Introspection ===

    @Test
    @Order(100)
    void introspectionSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { queryType { name } mutationType { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"));
    }

    // === Offset pagination ===

    @Test
    @Order(110)
    void listWithOffset() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(orderBy: { customer_id: ASC }, limit: 2, offset: 2) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(2)))
                .andExpect(jsonPath("$.data.testCustomer[0].first_name").value("Carol"));
    }

    // === Aggregate with WHERE filter ===

    @Test
    @Order(120)
    void aggregateWithFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testOrdersAggregate(where: { total_amount: { gt: 100 } }) { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testOrdersAggregate.count").value(3));
    }

    // === NIN filter ===

    @Test
    @Order(130)
    void whereNinFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { customer_id: { nin: [1, 2, 3] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(2))));
    }

    // === StartsWith, EndsWith, Contains ===

    @Test
    @Order(131)
    void whereStartsWithFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { first_name: { startsWith: \"Al\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    @Test
    @Order(132)
    void whereEndsWithFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { email: { endsWith: \"@example.com\" } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(133)
    void whereContainsFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { first_name: { contains: \"li\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    // === OR / AND / NOT filters ===

    @Test
    @Order(140)
    void whereOrFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { or: [{ first_name: { eq: \"Alice\" } }, { first_name: { eq: \"Bob\" } }] }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    @Test
    @Order(141)
    void whereAndFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { and: [{ active: { eq: true } }, { first_name: { eq: \"Alice\" } }] }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    @Test
    @Order(142)
    void whereNotFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { not: { first_name: { eq: \"Alice\" } } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === isNull / isNotNull filter operators ===

    @Test
    @Order(150)
    void whereIsNullFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { email: { isNull: true } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer").isArray());
    }

    @Test
    @Order(151)
    void whereIsNotNullFilter() throws Exception {
        // All 5 customers have email set, so should get all 5
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { email: { isNotNull: true } }) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(5)));
    }

    // === notIn filter ===

    @Test
    @Order(152)
    void whereNotInFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(where: { customer_id: { notIn: [1, 2] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(3))));
    }

    // === String ordering (e2e tests pass "DESC" as string, not enum) ===

    @Test
    @Order(160)
    void orderByDescString() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testCustomer(orderBy: { customer_id: \"DESC\" }, limit: 3, where: { customer_id: { lte: 5 } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testCustomer[0].customer_id").value(5))
                .andExpect(jsonPath("$.data.testCustomer[1].customer_id").value(4));
    }

    // === Update with id + input style (e2e test style) ===

    @Test
    @Order(170)
    void updateWithIdArg() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { updateTestCustomer(where: { customer_id: { eq: 2 } }, input: { first_name: \"Bobby\" }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTestCustomer[0].first_name").value("Bobby"));
    }

    // === Views — read-only, no mutation fields ===

    @Test
    @Order(180)
    void queryView() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testActiveCustomers(limit: 10) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testActiveCustomers", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(181)
    void viewHasNoMutationFields() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __type(name: \"Mutation\") { fields { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'createTestActiveCustomers')]").doesNotExist());
    }

    // === ENUM columns (MySQL ENUM type) ===

    @Test
    @Order(190)
    void queryTaskWithEnumColumns() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testTask(orderBy: { task_id: ASC }, limit: 3) { task_id title status priority } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testTask", hasSize(3)))
                .andExpect(jsonPath("$.data.testTask[0].title").value("Fix bug"))
                .andExpect(jsonPath("$.data.testTask[0].status").value("done"))
                .andExpect(jsonPath("$.data.testTask[0].priority").value("high"));
    }

    @Test
    @Order(191)
    void filterTaskByEnumStatus() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testTask(where: { status: { eq: \"done\" } }) { task_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testTask", hasSize(1)));
    }

    // === Task FK to customer ===

    @Test
    @Order(192)
    void taskForwardFkToCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testTask(orderBy: { task_id: ASC }, limit: 1) { title testAssignedTo { first_name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testTask[0].testAssignedTo.first_name").value("Alice"));
    }
}
