package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SqlCompilerIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.max-rows", "30");
        return properties;
    }


    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws JsonProcessingException {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === List queries ===

    @Test
    @Order(1)
    void listCustomers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { customer_id: ASC }) { customer_id first_name last_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void listWithWhereFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { active: { eq: true } }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(4)));
    }

    @Test
    @Order(3)
    void listWithLimit() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(limit: 2) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)));
    }

    @Test
    @Order(4)
    void listWithOrderBy() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { first_name: DESC }, limit: 1) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Eve"));
    }

    // === Forward FK ===

    @Test
    @Order(5)
    void forwardFkRelationship() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(limit: 1) { order_id total_amount testSchemaCustomerId { first_name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders[0].testSchemaCustomerId.first_name").value("Alice"));
    }

    // === Reverse FK ===

    @Test
    @Order(6)
    void reverseFkRelationship() throws Exception {
        // Reverse FK field: orders.customer_id FK -> on customer -> field name is now "testSchemaOrders" (child table name)
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { eq: 1 } }) { first_name testSchemaOrders { order_id total_amount } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"))
                .expect(jsonPath("$.data.testSchemaCustomer[0].testSchemaOrders", hasSize(2)));
    }

    // === Connection ===

    @Test
    @Order(10)
    void connectionBasic() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 2) { edges { cursor node { customer_id first_name } } pageInfo { hasNextPage hasPreviousPage } totalCount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(2)))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges[0].cursor").isString())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges[0].node.first_name").value("Alice"))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasNextPage").value(true))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasPreviousPage").value(false))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.totalCount").value(5));
    }

    @Test
    @Order(11)
    void connectionAfterCursor() throws Exception {
        // Get first page
        var result = http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 2) { edges { cursor node { customer_id } } } }"), "Content-Type", "application/json");
        String body = result.body();
        String cursor = result.jsonPath("$.data.testSchemaCustomerConnection.edges[1].cursor");

        // Get second page using after cursor
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 2, after: \"" + cursor + "\") { edges { node { customer_id first_name } } pageInfo { hasPreviousPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges[0].node.customer_id").value(3))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasPreviousPage").value(true));
    }

    // === Aggregates ===

    @Test
    @Order(20)
    void aggregateCount() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerAggregate { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerAggregate.count").value(5));
    }

    @Test
    @Order(21)
    void aggregateSumAvgMinMax() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrdersAggregate { count sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.count").value(5))
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.max.total_amount").isString());
    }

    // === Mutations ===

    @Test
    @Order(30)
    void createCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createTestSchemaCustomer(input: { first_name: \"Test\", last_name: \"User\", email: \"test@test.com\" }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestSchemaCustomer.first_name").value("Test"))
                .expect(jsonPath("$.data.createTestSchemaCustomer.customer_id").isNumber());
    }

    @Test
    @Order(31)
    void updateCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { updateTestSchemaCustomer(where: { customer_id: { eq: 1 } }, input: { email: \"updated@test.com\" }) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestSchemaCustomer[0].email").value("updated@test.com"));
    }

    @Test
    @Order(32)
    void deleteCustomer() throws Exception {
        // Create a customer to delete
        var createResult = http.post("/test-proj/graphql", graphql("mutation { createTestSchemaCustomer(input: { first_name: \"ToDelete\", last_name: \"User\" }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk());
        int createdId = createResult.jsonPath("$.data.createTestSchemaCustomer.customer_id");

        http.post("/test-proj/graphql", graphql("mutation { deleteTestSchemaCustomer(where: { customer_id: { eq: " + createdId + " } }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.deleteTestSchemaCustomer[0].first_name").value("ToDelete"));
    }

    @Test
    @Order(33)
    void bulkCreateCustomers() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createManyTestSchemaCustomer(inputs: [{ first_name: \"Bulk1\", last_name: \"Test\" }, { first_name: \"Bulk2\", last_name: \"Test\" }]) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createManyTestSchemaCustomer", hasSize(2)));
    }

    @Test
    @Order(33)
    void bulkCreate_rowsSettingDifferentColumns_keepEveryValue_andDefaultTheRest() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createManyTestSchemaCustomer(inputs: ["
                                + "{ first_name: \"Mixed1\", last_name: \"Test\", active: false },"
                                + " { first_name: \"Mixed2\", last_name: \"Test\", email: \"m2@example.com\" }])"
                                + " { first_name email active } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createManyTestSchemaCustomer[0].active").value(false))
                .expect(jsonPath("$.data.createManyTestSchemaCustomer[0].email").value(nullValue()))
                .expect(jsonPath("$.data.createManyTestSchemaCustomer[1].email").value("m2@example.com"))
                .expect(jsonPath("$.data.createManyTestSchemaCustomer[1].active").value(true));
    }

    @Test
    @Order(34)
    void multiMutation_twoCreatesInSingleRequest() throws Exception {
        // Two mutations in one request — both must execute and both must be returned
        http.post("/test-proj/graphql", graphql("""
                            mutation {
                              c1: createTestSchemaCustomer(input: { first_name: "MultiMut1", last_name: "Test" }) { customer_id first_name }
                              c2: createTestSchemaCustomer(input: { first_name: "MultiMut2", last_name: "Test" }) { customer_id first_name }
                            }
                            """), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.c1.first_name").value("MultiMut1"))
                .expect(jsonPath("$.data.c2.first_name").value("MultiMut2"))
                .expect(jsonPath("$.data.c1.customer_id").isNumber())
                .expect(jsonPath("$.data.c2.customer_id").isNumber());
    }

    @Test
    @Order(35)
    void multiMutation_createAndUpdate() throws Exception {
        // Create one row then update another — both in same request
        // Update email (already changed by order-31 updateCustomer test, not relied on by later tests)
        http.post("/test-proj/graphql", graphql("""
                            mutation {
                              newRow: createTestSchemaCustomer(input: { first_name: "NewRow", last_name: "Test" }) { customer_id first_name }
                              updated: updateTestSchemaCustomer(where: { customer_id: { eq: 1 } }, input: { email: "multi-updated@test.com" }) { customer_id email }
                            }
                            """), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.newRow.first_name").value("NewRow"))
                .expect(jsonPath("$.data.updated[0].email").value("multi-updated@test.com"));
    }

    @Test
    @Order(36)
    void nestedInsert_orderWithItems() throws Exception {
        // Nested insert: create order + order_items in one mutation
        http.post("/test-proj/graphql", graphql("""
                            mutation {
                              createTestSchemaOrders(input: {
                                customer_id: 1
                                total_amount: 99.99
                                testSchemaOrderItems: {
                                  data: [
                                    { product_id: 101, quantity: 2, price: 49.99 }
                                    { product_id: 102, quantity: 1, price: 0.01 }
                                  ]
                                }
                              }) { order_id total_amount }
                            }
                            """), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestSchemaOrders.order_id").isNumber())
                .expect(jsonPath("$.data.createTestSchemaOrders.total_amount").value("99.99"));
    }

    // === Enhanced WHERE filters ===

    @Test
    @Order(40)
    void whereInFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { in: [1, 3] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)));
    }

    @Test
    @Order(41)
    void whereLikeFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { first_name: { like: \"A%\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(42)
    void whereGtLtFilters() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(where: { total_amount: { gt: 100 } }) { order_id total_amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    // === Introspection ===

    @Test
    @Order(50)
    void introspectionSchema() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __schema { queryType { name } mutationType { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                .expect(jsonPath("$.data.__schema.mutationType.name").value("Mutation"));
    }

    @Test
    @Order(51)
    void introspectionType() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __type(name: \"TestSchemaCustomer\") { name fields { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__type.name").value("TestSchemaCustomer"))
                .expect(jsonPath("$.data.__type.fields").isArray());
    }

    // === Fragments ===

    @Test
    @Order(60)
    void fragmentSpread() throws Exception {
        http.post("/test-proj/graphql", graphql("fragment CustomerFields on TestSchemaCustomer { customer_id first_name } { testSchemaCustomer(limit: 1) { ...CustomerFields last_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].customer_id").isNumber())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").isString())
                .expect(jsonPath("$.data.testSchemaCustomer[0].last_name").isString());
    }

    // === Phase 1A: Enum support ===

    @Test
    @Order(70)
    void enumColumnReturnsValue() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaTask(limit: 1, where: { priority: { eq: \"HIGH\" } }) { task_id title priority } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaTask[0].title").value("Fix bug"))
                .expect(jsonPath("$.data.testSchemaTask[0].priority").value("HIGH"));
    }

    @Test
    @Order(71)
    void enumFilterIn() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaTask(where: { priority: { in: [\"HIGH\", \"CRITICAL\"] } }) { task_id priority } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaTask", hasSize(2)));
    }

    // === Computed fields: reflected, never served (not tracked functions) ===

    @Test
    @Order(80)
    void computedField_isNotServed_evenToService() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(limit: 1, orderBy: { customer_id: ASC }) { first_name last_name customer_full_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data").doesNotExist())
                .expect(jsonPath("$.errors[0].message").value("Unknown field(s): customer_full_name"));
    }

    @Test
    @Order(81)
    void unknownRootMutation_isAnError_withNoData() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { noSuchMutation(id: 1) { id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data").doesNotExist())
                .expect(jsonPath("$.errors[0].message").value("Unknown field(s): noSuchMutation"));
    }

    // === Phase 1C: Bulk UPDATE by filter ===

    @Test
    @Order(90)
    void bulkUpdateByFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { updateTestSchemaTaskCollection(set: { priority: \"LOW\" }, filter: { priority: { eq: \"HIGH\" } }, atMost: 10) { task_id priority } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestSchemaTaskCollection[0].priority").value("LOW"));

        // Restore
        http.post("/test-proj/graphql", graphql("mutation { updateTestSchemaTask(input: { task_id: 1, priority: \"HIGH\" }) { task_id } }"), "Content-Type", "application/json");
    }

    // === Phase 1D: Bulk DELETE by filter ===

    @Test
    @Order(91)
    void bulkDeleteByFilter() throws Exception {
        // Insert temp data
        http.post("/test-proj/graphql", graphql("mutation { createTestSchemaTask(input: { title: \"temp1\", priority: \"LOW\" }) { task_id } }"), "Content-Type", "application/json");
        http.post("/test-proj/graphql", graphql("mutation { createTestSchemaTask(input: { title: \"temp2\", priority: \"LOW\" }) { task_id } }"), "Content-Type", "application/json");

        // Bulk delete by filter
        http.post("/test-proj/graphql", graphql("mutation { deleteFromTestSchemaTaskCollection(filter: { title: { like: \"temp%\" } }, atMost: 10) { task_id title } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.deleteFromTestSchemaTaskCollection", hasSize(2)));
    }

    // === Phase 1E: Upsert ===

    @Test
    @Order(92)
    void upsertInsertThenUpdate() throws Exception {
        // First call: inserts
        http.post("/test-proj/graphql", graphql("mutation { createTestSchemaCustomer(input: { first_name: \"Upsert\", last_name: \"Test\", email: \"upsert@test.com\" }, onConflict: { constraint: \"customer_pkey\", update_columns: [\"email\"] }) { customer_id first_name email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestSchemaCustomer.first_name").value("Upsert"));
    }

    // === Phase 1F: Nulls ordering ===

    @Test
    @Order(93)
    void orderByNullsLast() throws Exception {
        // task.assigned_to has NULLs possible, but our test data doesn't have nulls
        // Use email which could be null
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { email: AscNullsLast }, limit: 5) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)));
    }

    // === Phase 2A: Array columns ===

    @Test
    @Order(100)
    void arrayColumnReturned() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { eq: 1 } }) { first_name tags } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].tags").isArray())
                .expect(jsonPath("$.data.testSchemaCustomer[0].tags", hasSize(2)));
    }

    // === Phase 2B: JSONB columns ===

    @Test
    @Order(101)
    void jsonbColumnReturned() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { eq: 1 } }) { first_name metadata } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].metadata.vip").value(true));
    }

    // === Phase 2E: Aggregate with WHERE ===

    @Test
    @Order(102)
    void aggregateWithFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrdersAggregate(where: { total_amount: { gt: 100 } }) { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.count").value(3));
    }

    // === Phase 2F: Views ===

    @Test
    @Order(103)
    void queryView() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaActiveCustomers { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaActiveCustomers", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Phase 2C: Additional filter operators ===

    @Test
    @Order(104)
    void filterNin() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { nin: [1, 2, 3] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(105)
    void filterEndsWith() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { endsWith: \"@example.com\" } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Phase 1C/1D: Forward FK on task -> customer ===

    @Test
    @Order(106)
    void taskForwardFkToCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaTask(limit: 1, orderBy: { task_id: ASC }) { task_id title testSchemaAssignedTo { first_name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaTask[0].testSchemaAssignedTo.first_name").value("Alice"));
    }

    // === Phase 1G: Introspection with relationships ===

    @Test
    @Order(110)
    void introspectionShowsFkFields() throws Exception {
        // Customer type should show 'orders' reverse FK field and scalar fields
        http.post("/test-proj/graphql", graphql("{ __type(name: \"TestSchemaCustomer\") { name fields { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__type.name").value("TestSchemaCustomer"))
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'customer_id')]").exists())
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'first_name')]").exists());
    }

    @Test
    @Order(111)
    void introspectionEnumType() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __type(name: \"TestSchemaPriorityLevel\") { kind enumValues { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__type.kind").value("ENUM"))
                .expect(jsonPath("$.data.__type.enumValues", hasSize(4)));
    }

    // === Phase 2D: Distinct ===

    @Test
    @Order(120)
    void distinctOnQuery() throws Exception {
        // order_items has multiple rows per order_id. Distinct on order_id should deduplicate.
        http.post("/test-proj/graphql", graphql("{ testSchemaOrderItems(distinctOn: [\"order_id\"]) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrderItems", hasSize(5)));
    }

    // === Additional filter coverage ===

    @Test
    @Order(130)
    void filterIlike() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { first_name: { ilike: \"alice\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(1)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(131)
    void filterStartsWith() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { first_name: { startsWith: \"Al\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(1)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(132)
    void filterContains() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { first_name: { contains: \"li\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(1)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(133)
    void filterEqNull() throws Exception {
        // Customers with null email — earlier mutations may have added rows without email
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { eq: null } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    @Order(134)
    void filterNeqNull() throws Exception {
        // Customers with non-null email
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { neq: null } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(135)
    void filterIsNull() throws Exception {
        // Use { is: NULL } enum form
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { is: NULL } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(0))));
    }

    @Test
    @Order(136)
    void filterIsNotNull() throws Exception {
        // Use { is: NOT_NULL } enum form
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { is: NOT_NULL } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(137)
    void filterOrCombination() throws Exception {
        // OR: first_name = Alice OR first_name = Bob
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { or: [{ first_name: { eq: \"Alice\" } }, { first_name: { eq: \"Bob\" } }] }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)));
    }

    @Test
    @Order(138)
    void filterAndCombination() throws Exception {
        // AND: active = true AND first_name = Alice (email-based filter unreliable due to earlier mutations)
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { and: [{ active: { eq: true } }, { first_name: { eq: \"Alice\" } }] }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(1)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(139)
    void filterNotCombination() throws Exception {
        // NOT: not { first_name = Alice } -> should return 4 others
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { not: { first_name: { eq: \"Alice\" } } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(140)
    void filterGte() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(where: { total_amount: { gte: 250 } }) { order_id total_amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders", hasSize(2)));
    }

    @Test
    @Order(141)
    void filterLt() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(where: { total_amount: { lt: 100 } }) { order_id total_amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders", hasSize(3)));
    }

    @Test
    @Order(142)
    void filterLte() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(where: { total_amount: { lte: 100.50 } }) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders", hasSize(4)));
    }

    @Test
    @Order(143)
    void filterNeq() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { first_name: { neq: \"Alice\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === Offset pagination ===

    @Test
    @Order(150)
    void listWithOffset() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { customer_id: ASC }, limit: 2, offset: 2) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Carol"));
    }

    // === Multiple root fields ===

    @Test
    @Order(160)
    void multipleRootFields() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(limit: 1, orderBy: { customer_id: ASC }) { first_name } testSchemaOrders(limit: 1, orderBy: { order_id: ASC }) { order_id total_amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"))
                .expect(jsonPath("$.data.testSchemaOrders[0].order_id").value(1));
    }

    // === Error cases ===

    @Test
    @Order(170)
    void errorInvalidField() throws Exception {
        http.post("/test-proj/graphql", graphql("{ nonexistentTable { id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").isArray())
                .expect(jsonPath("$.errors[0].message").isString());
    }

    @Test
    @Order(171)
    void errorInvalidQuery() throws Exception {
        // Malformed GraphQL should return error
        http.post("/test-proj/graphql", graphql("{ invalid query syntax {{{"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").isArray());
    }

    // === Nulls ordering variants ===

    @Test
    @Order(180)
    void orderByAscNullsFirst() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { email: AscNullsFirst }, limit: 5) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)));
    }

    @Test
    @Order(181)
    void orderByDescNullsFirst() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { email: DescNullsFirst }, limit: 5) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)));
    }

    @Test
    @Order(182)
    void orderByDescNullsLast() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { email: DescNullsLast }, limit: 5) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)));
    }

    // === Connection with startCursor and endCursor ===

    @Test
    @Order(190)
    void connectionWithStartEndCursor() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 3) { edges { cursor node { customer_id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor } totalCount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(3)))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.startCursor").isString())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.endCursor").isString())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.totalCount").value(greaterThanOrEqualTo(5)));
    }

    // === Connection with where filter ===

    @Test
    @Order(191)
    void connectionWithWhereFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 10, where: { active: { eq: true } }) { edges { node { first_name } } totalCount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.totalCount").value(greaterThanOrEqualTo(4)));
    }

    // === Connection last (backward pagination) ===

    @Test
    @Order(192)
    void connectionLast() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(last: 2) { edges { node { customer_id first_name } } pageInfo { hasNextPage hasPreviousPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(2)));
    }

    // === Aggregate with sum/avg on orders ===

    @Test
    @Order(200)
    void aggregateSumOnly() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrdersAggregate { sum { total_amount } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.sum.total_amount").isString());
    }

    @Test
    @Order(201)
    void aggregateMinMax() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrdersAggregate { min { total_amount } max { total_amount } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.min.total_amount").isString())
                .expect(jsonPath("$.data.testSchemaOrdersAggregate.max.total_amount").isString());
    }

    // === Inline fragment ===

    @Test
    @Order(210)
    void inlineFragment() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(limit: 1, orderBy: { customer_id: ASC }) { ... on TestSchemaCustomer { customer_id first_name } last_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].customer_id").isNumber())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"))
                .expect(jsonPath("$.data.testSchemaCustomer[0].last_name").value("Smith"));
    }

    // === Limit + order sanity check ===

    @Test
    @Order(220)
    void queryWithLimitAndOrder() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(limit: 2, orderBy: { customer_id: ASC }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    // === Order items (composite PK table) — list ===

    @Test
    @Order(230)
    void listOrderItems() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrderItems(limit: 3, orderBy: { order_id: ASC }) { order_id product_id quantity price } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrderItems", hasSize(3)))
                .expect(jsonPath("$.data.testSchemaOrderItems[0].order_id").value(1));
    }

    // === Reverse FK: customer -> tasks ===

    @Test
    @Order(231)
    void reverseFkCustomerToTasks() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { eq: 1 } }) { first_name testSchemaTask { task_id title } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"))
                .expect(jsonPath("$.data.testSchemaCustomer[0].testSchemaTask", hasSize(2)));
    }

    // === Connection without totalCount (conditional totalCount) ===

    @Test
    @Order(240)
    void connectionWithoutTotalCount() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 2) { edges { node { first_name } } pageInfo { hasNextPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(2)))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasNextPage").value(true));
    }

    // === Aggregate count only (no sum/avg/min/max sub-selections) ===

    @Test
    @Order(250)
    void aggregateCountOnly() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaTaskAggregate { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaTaskAggregate.count").value(greaterThanOrEqualTo(2)));
    }

    // === isNull / isNotNull / notIn filter operators ===

    @Test
    @Order(260)
    void whereIsNullFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { isNull: true } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer").isArray());
    }

    @Test
    @Order(261)
    void whereIsNotNullFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { email: { isNotNull: true } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    @Order(262)
    void whereNotInFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(where: { customer_id: { notIn: [1, 2] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(3))));
    }

    // === String-value ordering (e2e tests pass "DESC" as string, not enum) ===

    @Test
    @Order(270)
    void orderByDescString() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { customer_id: \"DESC\" }, limit: 2) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer[0].customer_id").value(greaterThan(3)));
    }

    // === Update with separate id arg + input arg (e2e test style) ===

    @Test
    @Order(280)
    void updateWithIdArg() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { updateTestSchemaCustomer(where: { customer_id: { eq: 2 } }, input: { first_name: \"Bobby\" }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestSchemaCustomer[0].first_name").value("Bobby"));
    }

    // === Query-path variable threading ($var resolution) ===

    @Test
    @Order(290)
    void queryPathResolvesScalarVariable() throws Exception {
        // $active passed as a variable, not an inline literal. Before the fix
        // the query path bound the literal string "$active" which Postgres
        // rejected with a type error.
        String query = "query GetActive($active: Boolean!) { testSchemaCustomer(where: { active: { eq: $active } }) { customer_id } }";
        String body = mapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("active", true)
        ));
        http.post("/test-proj/graphql", body, "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(greaterThanOrEqualTo(1))))
                .expect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @Order(291)
    void queryPathResolvesLimitVariable() throws Exception {
        // $n threaded into LIMIT clause via FilterBuilder.applyLimit
        String query = "query GetN($n: Int!) { testSchemaCustomer(limit: $n) { customer_id } }";
        String body = mapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("n", 2)
        ));
        http.post("/test-proj/graphql", body, "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(2)));
    }

    // === Top-level field alias preservation ===

    @Test
    @Order(295)
    void topLevelAliasesKeepDistinctResults() throws Exception {
        // Two aliases on the same root field with different filters. Before
        // the fix both subqueries executed but jsonb_build_object collapsed
        // the duplicate "testSchemaCustomer" key, dropping the first result.
        String query = "{ "
                + "alpha: testSchemaCustomer(where: { customer_id: { eq: 1 } }) { customer_id first_name } "
                + "bravo: testSchemaCustomer(where: { customer_id: { eq: 2 } }) { customer_id first_name } "
                + "}";
        http.post("/test-proj/graphql", graphql(query), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.alpha", hasSize(1)))
                .expect(jsonPath("$.data.alpha[0].customer_id").value(1))
                .expect(jsonPath("$.data.bravo", hasSize(1)))
                .expect(jsonPath("$.data.bravo[0].customer_id").value(2));
    }

    // === Query-depth DoS guard (app.max-query-depth unset -> default 15 applies) ===

    @Test
    @Order(300)
    void deeplyNestedQueryRejectedByDefaultDepthLimit() throws Exception {
        // The test profile leaves app.max-query-depth unset, so the wired default (15) applies.
        // A query nested beyond 15 levels must return a clear GraphQL error, not a 500.
        StringBuilder sb = new StringBuilder("{ ");
        int depth = 20;
        for (int i = 0; i < depth - 1; i++) {
            sb.append("f").append(i).append(" { ");
        }
        sb.append("f").append(depth - 1);
        for (int i = 0; i < depth - 1; i++) {
            sb.append(" }");
        }
        String tooDeep = sb.append(" }").toString();

        http.post("/test-proj/graphql", graphql(tooDeep), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].message", containsString("exceeds maximum allowed depth of 15")));
    }
}
