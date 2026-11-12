package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlIntegrationTest extends EngineIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-mysql.sql");

    @Override
    public Map<String, String> getProperties() {
        return EngineProperties.mysql(mysql, "test-proj");
    }

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === List queries ===

    @Test
    @Order(1)
    void listCustomers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(orderBy: { customer_id: ASC }, limit: 5) { customer_id first_name last_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(5)))
                .expect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void listWithWhereFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { active: { eq: true } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(4)));
    }

    @Test
    @Order(3)
    void listWithLimit() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(limit: 2) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    // === Forward FK ===

    @Test
    @Order(5)
    void forwardFkRelationship() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrders(limit: 1, orderBy: { order_id: ASC }) { order_id total_amount testCustomerId { first_name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrders[0].testCustomerId.first_name").value("Alice"));
    }

    // === Reverse FK ===

    @Test
    @Order(6)
    void reverseFkRelationship() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { customer_id: { eq: 1 } }) { first_name testOrders { order_id } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer[0].testOrders", hasSize(2)));
    }

    // === Connection ===

    @Test
    @Order(10)
    void connectionBasic() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomerConnection(first: 2) { edges { cursor node { customer_id first_name } } pageInfo { hasNextPage } totalCount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomerConnection.edges", hasSize(2)))
                .expect(jsonPath("$.data.testCustomerConnection.pageInfo.hasNextPage").value(true))
                .expect(jsonPath("$.data.testCustomerConnection.totalCount").value(5));
    }

    // === Aggregates ===

    @Test
    @Order(20)
    void aggregateCount() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomerAggregate { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomerAggregate.count").value(5));
    }

    // === Mutations ===

    @Test
    @Order(30)
    void createCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createTestCustomer(input: { first_name: \"Test\", last_name: \"MySQL\" }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestCustomer.first_name").value("Test"))
                .expect(jsonPath("$.data.createTestCustomer.customer_id").isNumber());
    }

    @Test
    @Order(31)
    void updateCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { updateTestCustomer(where: { customer_id: { eq: 1 } }, input: { email: \"updated@mysql.com\" }) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestCustomer[0].email").value("updated@mysql.com"));
    }

    @Test
    @Order(32)
    void deleteCustomer() throws Exception {
        // Create then capture the ID
        var createResult = http.post("/test-proj/graphql", graphql("mutation { createTestCustomer(input: { first_name: \"ToDelete\", last_name: \"User\" }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk());
        int createdId = createResult.jsonPath("$.data.createTestCustomer.customer_id");

        http.post("/test-proj/graphql", graphql("mutation { deleteTestCustomer(where: { customer_id: { eq: " + createdId + " } }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.deleteTestCustomer[0].first_name").value("ToDelete"));
    }

    // === Bulk create (createMany) ===

    @Test
    @Order(33)
    void bulkCreateCustomers() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createManyTestCustomer(inputs: [{ first_name: \"Bulk1\", last_name: \"Test\" }, { first_name: \"Bulk2\", last_name: \"Test\" }]) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createManyTestCustomer", hasSize(2)));
    }

    // === WHERE filters ===

    @Test
    @Order(40)
    void whereNeqFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { first_name: { neq: \"Alice\" } }, limit: 10) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(41)
    void whereGtFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrders(where: { total_amount: { gt: 100 } }) { order_id total_amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrders", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @Order(42)
    void whereGteFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrders(where: { total_amount: { gte: 250 } }) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrders", hasSize(2)));
    }

    @Test
    @Order(43)
    void whereLtFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrders(where: { total_amount: { lt: 100 } }) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrders", hasSize(2)));
    }

    @Test
    @Order(44)
    void whereLteFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrders(where: { total_amount: { lte: 100.50 } }) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrders", hasSize(3)));
    }

    @Test
    @Order(45)
    void whereInFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { customer_id: { in: [1, 3] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    @Test
    @Order(46)
    void whereLikeFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { first_name: { like: \"A%\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(47)
    void whereIlikeFilter() throws Exception {
        // MySQL uses LOWER(col) LIKE LOWER(val) for ilike
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { first_name: { ilike: \"alice\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    // === ORDER BY ===

    @Test
    @Order(50)
    void orderByDesc() throws Exception {
        // Earlier mutations add customers — filter to original 5 to ensure stable ordering
        http.post("/test-proj/graphql", graphql("{ testCustomer(orderBy: { first_name: DESC }, limit: 1, where: { customer_id: { lte: 5 } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer[0].first_name").value("Eve"));
    }

    // === Aggregates ===

    @Test
    @Order(60)
    void aggregateSumAvgMinMax() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrdersAggregate { count sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrdersAggregate.count").value(5))
                .expect(jsonPath("$.data.testOrdersAggregate.sum.total_amount").isString())
                .expect(jsonPath("$.data.testOrdersAggregate.max.total_amount").isString());
    }

    // === Connection with cursor pagination ===

    @Test
    @Order(70)
    void connectionAfterCursor() throws Exception {
        // Get first page
        var result = http.post("/test-proj/graphql", graphql("{ testCustomerConnection(first: 2) { edges { cursor node { customer_id } } } }"), "Content-Type", "application/json");
        String cursor = result.jsonPath("$.data.testCustomerConnection.edges[1].cursor");

        // Get second page using after cursor
        http.post("/test-proj/graphql", graphql("{ testCustomerConnection(first: 2, after: \"" + cursor + "\") { edges { node { customer_id first_name } } pageInfo { hasPreviousPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomerConnection.edges[0].node.customer_id").value(3))
                .expect(jsonPath("$.data.testCustomerConnection.pageInfo.hasPreviousPage").value(true));
    }

    // === Multiple root fields ===

    @Test
    @Order(80)
    void multipleRootFields() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(limit: 1, orderBy: { customer_id: ASC }) { first_name } testOrders(limit: 1, orderBy: { order_id: ASC }) { order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer[0].first_name").value("Alice"))
                .expect(jsonPath("$.data.testOrders[0].order_id").value(1));
    }

    // === Error cases ===

    @Test
    @Order(90)
    void errorInvalidField() throws Exception {
        http.post("/test-proj/graphql", graphql("{ nonexistentTable { id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").isArray());
    }

    // === Introspection ===

    @Test
    @Order(100)
    void introspectionSchema() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __schema { queryType { name } mutationType { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__schema.queryType.name").value("Query"));
    }

    // === Offset pagination ===

    @Test
    @Order(110)
    void listWithOffset() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(orderBy: { customer_id: ASC }, limit: 2, offset: 2) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(2)))
                .expect(jsonPath("$.data.testCustomer[0].first_name").value("Carol"));
    }

    // === Aggregate with WHERE filter ===

    @Test
    @Order(120)
    void aggregateWithFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testOrdersAggregate(where: { total_amount: { gt: 100 } }) { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testOrdersAggregate.count").value(3));
    }

    // === NIN filter ===

    @Test
    @Order(130)
    void whereNinFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { customer_id: { nin: [1, 2, 3] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(2))));
    }

    // === StartsWith, EndsWith, Contains ===

    @Test
    @Order(131)
    void whereStartsWithFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { first_name: { startsWith: \"Al\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    @Test
    @Order(132)
    void whereEndsWithFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { email: { endsWith: \"@example.com\" } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(133)
    void whereContainsFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { first_name: { contains: \"li\" } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    // === OR / AND / NOT filters ===

    @Test
    @Order(140)
    void whereOrFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { or: [{ first_name: { eq: \"Alice\" } }, { first_name: { eq: \"Bob\" } }] }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(2)));
    }

    @Test
    @Order(141)
    void whereAndFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { and: [{ active: { eq: true } }, { first_name: { eq: \"Alice\" } }] }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(1)));
    }

    @Test
    @Order(142)
    void whereNotFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { not: { first_name: { eq: \"Alice\" } } }) { first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(4))));
    }

    // === isNull / isNotNull filter operators ===

    @Test
    @Order(150)
    void whereIsNullFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { email: { isNull: true } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer").isArray());
    }

    @Test
    @Order(151)
    void whereIsNotNullFilter() throws Exception {
        // All 5 customers have email set, so should get all 5
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { email: { isNotNull: true } }) { customer_id email } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(5)));
    }

    // === notIn filter ===

    @Test
    @Order(152)
    void whereNotInFilter() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { customer_id: { notIn: [1, 2] } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer", hasSize(greaterThanOrEqualTo(3))));
    }

    // === String ordering (e2e tests pass "DESC" as string, not enum) ===

    @Test
    @Order(160)
    void orderByDescString() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(orderBy: { customer_id: \"DESC\" }, limit: 3, where: { customer_id: { lte: 5 } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testCustomer[0].customer_id").value(5))
                .expect(jsonPath("$.data.testCustomer[1].customer_id").value(4));
    }

    // === Update with id + input style (e2e test style) ===

    @Test
    @Order(170)
    void updateWithIdArg() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { updateTestCustomer(where: { customer_id: { eq: 2 } }, input: { first_name: \"Bobby\" }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.updateTestCustomer[0].first_name").value("Bobby"));
    }

    // === Views — read-only, no mutation fields ===

    @Test
    @Order(180)
    void queryView() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testActiveCustomers(limit: 10) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testActiveCustomers", hasSize(greaterThanOrEqualTo(4))));
    }

    @Test
    @Order(181)
    void viewHasNoMutationFields() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __type(name: \"Mutation\") { fields { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__type.fields[?(@.name == 'createTestActiveCustomers')]").doesNotExist());
    }

    // === ENUM columns (MySQL ENUM type) ===

    @Test
    @Order(190)
    void queryTaskWithEnumColumns() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testTask(orderBy: { task_id: ASC }, limit: 3) { task_id title status priority } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testTask", hasSize(3)))
                .expect(jsonPath("$.data.testTask[0].title").value("Fix bug"))
                .expect(jsonPath("$.data.testTask[0].status").value("done"))
                .expect(jsonPath("$.data.testTask[0].priority").value("high"));
    }

    @Test
    @Order(191)
    void filterTaskByEnumStatus() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testTask(where: { status: { eq: \"done\" } }) { task_id title } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testTask", hasSize(1)));
    }

    // === Task FK to customer ===

    @Test
    @Order(192)
    void taskForwardFkToCustomer() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testTask(orderBy: { task_id: ASC }, limit: 1) { title testAssignedTo { first_name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testTask[0].testAssignedTo.first_name").value("Alice"));
    }

    // === Database refusals carry their reason (EXC-559) ===

    @Test
    @Order(200)
    void aDuplicateKey_isAUniqueViolationNamingTheKey() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createTestCustomer(input: { customer_id: 1, first_name: \"A\", "
                                + "last_name: \"B\" }) { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("unique_violation"))
                .expect(jsonPath("$.errors[0].extensions.constraint").value("PRIMARY"));
    }

    @Test
    @Order(201)
    void aMissingParent_isAForeignKeyViolationWithoutTheSchema() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createTestOrders(input: { customer_id: 99999, total_amount: 1.5 }) "
                                + "{ order_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors[0].extensions.code").value("foreign_key_violation"))
                .expect(jsonPath("$.errors[0].message", not(containsString("`test`"))));
    }

    @Test
    @Order(202)
    void aMalformedNumberFilter_namesTheColumn() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testCustomer(where: { customer_id: { eq: \"abc\" } }) { customer_id } }"), "Content-Type", "application/json")
                .expect(jsonPath("$.errors[0].extensions.code").value("invalid_value"))
                .expect(jsonPath("$.errors[0].extensions.column").value("customer_id"));
    }
}
