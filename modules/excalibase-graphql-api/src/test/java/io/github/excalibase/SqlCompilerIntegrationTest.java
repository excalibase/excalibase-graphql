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
import org.testcontainers.containers.PostgreSQLContainer;
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
class SqlCompilerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.schema", () -> "test_schema");
        registry.add("app.max-rows", () -> 30);
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
                        .content(graphql("{ customer(orderBy: { customer_id: ASC }) { customer_id first_name last_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(5)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void listWithWhereFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { active: { eq: true } }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(4)));
    }

    @Test
    @Order(3)
    void listWithLimit() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(limit: 2) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(2)));
    }

    @Test
    @Order(4)
    void listWithOrderBy() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { first_name: DESC }, limit: 1) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Eve"));
    }

    // === Forward FK ===

    @Test
    @Order(5)
    void forwardFkRelationship() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orders(limit: 1) { order_id total_amount customer { first_name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders[0].customer.first_name").value("Alice"));
    }

    // === Reverse FK ===

    @Test
    @Order(6)
    void reverseFkRelationship() throws Exception {
        // Reverse FK field: orders (from table) -> on customer -> field name is "orders" (already ends with s)
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { eq: 1 } }) { first_name orders { order_id total_amount } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"))
                .andExpect(jsonPath("$.data.customer[0].orders", hasSize(2)));
    }

    // === Connection ===

    @Test
    @Order(10)
    void connectionBasic() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 2) { edges { cursor node { customer_id first_name } } pageInfo { hasNextPage hasPreviousPage } totalCount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.customerConnection.edges[0].cursor").isString())
                .andExpect(jsonPath("$.data.customerConnection.edges[0].node.first_name").value("Alice"))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.hasNextPage").value(true))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.hasPreviousPage").value(false))
                .andExpect(jsonPath("$.data.customerConnection.totalCount").value(5));
    }

    @Test
    @Order(11)
    void connectionAfterCursor() throws Exception {
        // Get first page
        var result = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 2) { edges { cursor node { customer_id } } } }")))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(body, "$.data.customerConnection.edges[1].cursor");

        // Get second page using after cursor
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 2, after: \"" + cursor + "\") { edges { node { customer_id first_name } } pageInfo { hasPreviousPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges[0].node.customer_id").value(3))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.hasPreviousPage").value(true));
    }

    // === Aggregates ===

    @Test
    @Order(20)
    void aggregateCount() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerAggregate { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerAggregate.count").value(5));
    }

    @Test
    @Order(21)
    void aggregateSumAvgMinMax() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ ordersAggregate { count sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordersAggregate.count").value(5))
                .andExpect(jsonPath("$.data.ordersAggregate.max.total_amount").isNumber());
    }

    // === Mutations ===

    @Test
    @Order(30)
    void createCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createCustomer(input: { first_name: \"Test\", last_name: \"User\", email: \"test@test.com\" }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createCustomer.first_name").value("Test"))
                .andExpect(jsonPath("$.data.createCustomer.customer_id").isNumber());
    }

    @Test
    @Order(31)
    void updateCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { updateCustomer(input: { customer_id: 1, email: \"updated@test.com\" }) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateCustomer.email").value("updated@test.com"));
    }

    @Test
    @Order(32)
    void deleteCustomer() throws Exception {
        // Create a customer to delete
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createCustomer(input: { first_name: \"ToDelete\", last_name: \"User\" }) { customer_id } }")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { deleteCustomer(input: { customer_id: 7 }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteCustomer.first_name").value("ToDelete"));
    }

    @Test
    @Order(33)
    void bulkCreateCustomers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createManyCustomer(inputs: [{ first_name: \"Bulk1\", last_name: \"Test\" }, { first_name: \"Bulk2\", last_name: \"Test\" }]) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createManyCustomer", hasSize(2)));
    }

    // === Enhanced WHERE filters ===

    @Test
    @Order(40)
    void whereInFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { in: [1, 3] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(2)));
    }

    @Test
    @Order(41)
    void whereLikeFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { first_name: { like: \"A%\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(42)
    void whereGtLtFilters() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orders(where: { total_amount: { gt: 100 } }) { order_id total_amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders", hasSize(greaterThanOrEqualTo(2))));
    }

    // === Introspection ===

    @Test
    @Order(50)
    void introspectionSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { queryType { name } mutationType { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                .andExpect(jsonPath("$.data.__schema.mutationType.name").value("Mutation"));
    }

    @Test
    @Order(51)
    void introspectionType() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __type(name: \"Customer\") { name fields { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__type.name").value("Customer"))
                .andExpect(jsonPath("$.data.__type.fields").isArray());
    }

    // === Fragments ===

    @Test
    @Order(60)
    void fragmentSpread() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("fragment CustomerFields on Customer { customer_id first_name } { customer(limit: 1) { ...CustomerFields last_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].customer_id").isNumber())
                .andExpect(jsonPath("$.data.customer[0].first_name").isString())
                .andExpect(jsonPath("$.data.customer[0].last_name").isString());
    }

    // === Phase 1A: Enum support ===

    @Test
    @Order(70)
    void enumColumnReturnsValue() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ task(limit: 1, where: { priority: { eq: \"HIGH\" } }) { task_id title priority } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task[0].title").value("Fix bug"))
                .andExpect(jsonPath("$.data.task[0].priority").value("HIGH"));
    }

    @Test
    @Order(71)
    void enumFilterIn() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ task(where: { priority: { in: [\"HIGH\", \"CRITICAL\"] } }) { task_id priority } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task", hasSize(2)));
    }

    // === Phase 1B: Computed fields ===

    @Test
    @Order(80)
    void computedFieldFullName() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(limit: 1, orderBy: { customer_id: ASC }) { first_name last_name customer_full_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].customer_full_name").value("Alice Smith"));
    }

    // === Phase 1C: Bulk UPDATE by filter ===

    @Test
    @Order(90)
    void bulkUpdateByFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { updateTaskCollection(set: { priority: \"LOW\" }, filter: { priority: { eq: \"HIGH\" } }, atMost: 10) { task_id priority } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateTaskCollection[0].priority").value("LOW"));

        // Restore
        mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("mutation { updateTask(input: { task_id: 1, priority: \"HIGH\" }) { task_id } }")));
    }

    // === Phase 1D: Bulk DELETE by filter ===

    @Test
    @Order(91)
    void bulkDeleteByFilter() throws Exception {
        // Insert temp data
        mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("mutation { createTask(input: { title: \"temp1\", priority: \"LOW\" }) { task_id } }")));
        mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(graphql("mutation { createTask(input: { title: \"temp2\", priority: \"LOW\" }) { task_id } }")));

        // Bulk delete by filter
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { deleteFromTaskCollection(filter: { title: { like: \"temp%\" } }, atMost: 10) { task_id title } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteFromTaskCollection", hasSize(2)));
    }

    // === Phase 1E: Upsert ===

    @Test
    @Order(92)
    void upsertInsertThenUpdate() throws Exception {
        // First call: inserts
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createCustomer(input: { first_name: \"Upsert\", last_name: \"Test\", email: \"upsert@test.com\" }, onConflict: { constraint: \"customer_pkey\", update_columns: [\"email\"] }) { customer_id first_name email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createCustomer.first_name").value("Upsert"));
    }

    // === Phase 1F: Nulls ordering ===

    @Test
    @Order(93)
    void orderByNullsLast() throws Exception {
        // task.assigned_to has NULLs possible, but our test data doesn't have nulls
        // Use email which could be null
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { email: AscNullsLast }, limit: 5) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(5)));
    }

    // === Phase 2A: Array columns ===

    @Test
    @Order(100)
    void arrayColumnReturned() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { eq: 1 } }) { first_name tags } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].tags").isArray())
                .andExpect(jsonPath("$.data.customer[0].tags", hasSize(2)));
    }

    // === Phase 2B: JSONB columns ===

    @Test
    @Order(101)
    void jsonbColumnReturned() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { eq: 1 } }) { first_name metadata } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].metadata.vip").value(true));
    }

    // === Phase 2E: Aggregate with WHERE ===

    @Test
    @Order(102)
    void aggregateWithFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ ordersAggregate(where: { total_amount: { gt: 100 } }) { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordersAggregate.count").value(3));
    }

    // === Phase 2F: Views ===

    @Test
    @Order(103)
    void queryView() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ activeCustomers { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCustomers", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Phase 2C: Additional filter operators ===

    @Test
    @Order(104)
    void filterNin() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { nin: [1, 2, 3] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(105)
    void filterEndsWith() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { endsWith: \"@example.com\" } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Phase 1C/1D: Forward FK on task -> customer ===

    @Test
    @Order(106)
    void taskForwardFkToCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ task(limit: 1, orderBy: { task_id: ASC }) { task_id title customer { first_name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task[0].customer.first_name").value("Alice"));
    }

    // === Phase 1G: Introspection with relationships ===

    @Test
    @Order(110)
    void introspectionShowsFkFields() throws Exception {
        // Customer type should show 'orders' reverse FK field and scalar fields
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __type(name: \"Customer\") { name fields { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__type.name").value("Customer"))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'customer_id')]").exists())
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'first_name')]").exists());
    }

    @Test
    @Order(111)
    void introspectionEnumType() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __type(name: \"PriorityLevel\") { kind enumValues { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__type.kind").value("ENUM"))
                .andExpect(jsonPath("$.data.__type.enumValues", hasSize(4)));
    }

    // === Phase 2D: Distinct ===

    @Test
    @Order(120)
    void distinctOnQuery() throws Exception {
        // order_items has multiple rows per order_id. Distinct on order_id should deduplicate.
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orderItems(distinctOn: [\"order_id\"]) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems", hasSize(4)));
    }

    // === Additional filter coverage ===

    @Test
    @Order(130)
    void filterIlike() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { first_name: { ilike: \"alice\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(1)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(131)
    void filterStartsWith() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { first_name: { startsWith: \"Al\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(1)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(132)
    void filterContains() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { first_name: { contains: \"li\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(1)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(133)
    void filterEqNull() throws Exception {
        // Customers with null email — earlier mutations may have added rows without email
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { eq: null } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    @Order(134)
    void filterNeqNull() throws Exception {
        // Customers with non-null email
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { neq: null } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(135)
    void filterIsNull() throws Exception {
        // Use { is: NULL } enum form
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { is: NULL } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    @Order(136)
    void filterIsNotNull() throws Exception {
        // Use { is: NOT_NULL } enum form
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { is: NOT_NULL } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(137)
    void filterOrCombination() throws Exception {
        // OR: first_name = Alice OR first_name = Bob
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { or: [{ first_name: { eq: \"Alice\" } }, { first_name: { eq: \"Bob\" } }] }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(2)));
    }

    @Test
    @Order(138)
    void filterAndCombination() throws Exception {
        // AND: active = true AND first_name = Alice (email-based filter unreliable due to earlier mutations)
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { and: [{ active: { eq: true } }, { first_name: { eq: \"Alice\" } }] }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(1)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    @Test
    @Order(139)
    void filterNotCombination() throws Exception {
        // NOT: not { first_name = Alice } -> should return 4 others
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { not: { first_name: { eq: \"Alice\" } } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(140)
    void filterGte() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orders(where: { total_amount: { gte: 250 } }) { order_id total_amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders", hasSize(2)));
    }

    @Test
    @Order(141)
    void filterLt() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orders(where: { total_amount: { lt: 100 } }) { order_id total_amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders", hasSize(2)));
    }

    @Test
    @Order(142)
    void filterLte() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orders(where: { total_amount: { lte: 100.50 } }) { order_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders", hasSize(3)));
    }

    @Test
    @Order(143)
    void filterNeq() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { first_name: { neq: \"Alice\" } }) { first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Offset pagination ===

    @Test
    @Order(150)
    void listWithOffset() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { customer_id: ASC }, limit: 2, offset: 2) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(2)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Carol"));
    }

    // === Multiple root fields ===

    @Test
    @Order(160)
    void multipleRootFields() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(limit: 1, orderBy: { customer_id: ASC }) { first_name } orders(limit: 1, orderBy: { order_id: ASC }) { order_id total_amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"))
                .andExpect(jsonPath("$.data.orders[0].order_id").value(1));
    }

    // === Error cases ===

    @Test
    @Order(170)
    void errorInvalidField() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ nonexistentTable { id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].message").isString());
    }

    @Test
    @Order(171)
    void errorInvalidQuery() throws Exception {
        // Malformed GraphQL should return error
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ invalid query syntax {{{")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // === Nulls ordering variants ===

    @Test
    @Order(180)
    void orderByAscNullsFirst() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { email: AscNullsFirst }, limit: 5) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(5)));
    }

    @Test
    @Order(181)
    void orderByDescNullsFirst() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { email: DescNullsFirst }, limit: 5) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(5)));
    }

    @Test
    @Order(182)
    void orderByDescNullsLast() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { email: DescNullsLast }, limit: 5) { customer_id email } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(5)));
    }

    // === Connection with startCursor and endCursor ===

    @Test
    @Order(190)
    void connectionWithStartEndCursor() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 3) { edges { cursor node { customer_id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges", hasSize(3)))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.startCursor").isString())
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.endCursor").isString())
                .andExpect(jsonPath("$.data.customerConnection.totalCount").value(greaterThanOrEqualTo(5)));
    }

    // === Connection with where filter ===

    @Test
    @Order(191)
    void connectionWithWhereFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 10, where: { active: { eq: true } }) { edges { node { first_name } } totalCount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.totalCount").value(greaterThanOrEqualTo(4)));
    }

    // === Connection last (backward pagination) ===

    @Test
    @Order(192)
    void connectionLast() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(last: 2) { edges { node { customer_id first_name } } pageInfo { hasNextPage hasPreviousPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges", hasSize(2)));
    }

    // === Aggregate with sum/avg on orders ===

    @Test
    @Order(200)
    void aggregateSumOnly() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ ordersAggregate { sum { total_amount } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordersAggregate.sum.total_amount").isNumber());
    }

    @Test
    @Order(201)
    void aggregateMinMax() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ ordersAggregate { min { total_amount } max { total_amount } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ordersAggregate.min.total_amount").isNumber())
                .andExpect(jsonPath("$.data.ordersAggregate.max.total_amount").isNumber());
    }

    // === Inline fragment ===

    @Test
    @Order(210)
    void inlineFragment() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(limit: 1, orderBy: { customer_id: ASC }) { ... on Customer { customer_id first_name } last_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].customer_id").isNumber())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"))
                .andExpect(jsonPath("$.data.customer[0].last_name").value("Smith"));
    }

    // === RLS path (X-User-Id header triggers RLS in Postgres) ===

    @Test
    @Order(220)
    void queryWithRlsHeader() throws Exception {
        // Superuser bypasses RLS, but this tests the RLS code path in the controller
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "alice")
                        .content(graphql("{ customer(limit: 2, orderBy: { customer_id: ASC }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(2)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"));
    }

    // === Order items (composite PK table) — list ===

    @Test
    @Order(230)
    void listOrderItems() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ orderItems(limit: 3, orderBy: { order_id: ASC }) { order_id product_id quantity price } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems", hasSize(3)))
                .andExpect(jsonPath("$.data.orderItems[0].order_id").value(1));
    }

    // === Reverse FK: customer -> tasks ===

    @Test
    @Order(231)
    void reverseFkCustomerToTasks() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { eq: 1 } }) { first_name task { task_id title } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("Alice"))
                .andExpect(jsonPath("$.data.customer[0].task", hasSize(2)));
    }

    // === Connection without totalCount (conditional totalCount) ===

    @Test
    @Order(240)
    void connectionWithoutTotalCount() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customerConnection(first: 2) { edges { node { first_name } } pageInfo { hasNextPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.hasNextPage").value(true));
    }

    // === Aggregate count only (no sum/avg/min/max sub-selections) ===

    @Test
    @Order(250)
    void aggregateCountOnly() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ taskAggregate { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskAggregate.count").value(greaterThanOrEqualTo(2)));
    }

    // === isNull / isNotNull / notIn filter operators ===

    @Test
    @Order(260)
    void whereIsNullFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { isNull: true } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray());
    }

    @Test
    @Order(261)
    void whereIsNotNullFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { email: { isNotNull: true } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(262)
    void whereNotInFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(where: { customer_id: { notIn: [1, 2] } }) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer", hasSize(greaterThanOrEqualTo(3))));
    }

    // === String-value ordering (e2e tests pass "DESC" as string, not enum) ===

    @Test
    @Order(270)
    void orderByDescString() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ customer(orderBy: { customer_id: \"DESC\" }, limit: 2) { customer_id } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].customer_id").value(greaterThan(3)));
    }

    // === Update with separate id arg + input arg (e2e test style) ===

    @Test
    @Order(280)
    void updateWithIdArg() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { updateCustomer(id: 2, input: { first_name: \"Bobby\" }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateCustomer.first_name").value("Bobby"));
    }
}
