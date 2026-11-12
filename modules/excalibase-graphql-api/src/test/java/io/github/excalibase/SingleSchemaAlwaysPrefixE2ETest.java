package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.hamcrest.Matchers.*;

/**
 * E2E test: when multiple schemas are discovered, types are ALWAYS prefixed.
 * This uses init.sql which creates test_schema with customer, orders, etc.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SingleSchemaAlwaysPrefixE2ETest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.database-type", "postgres");
        properties.put("app.max-rows", "30");
        return properties;
    }


    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === Prefixed queries (test_schema → testSchemaCustomer) ===

    @Test
    @Order(1)
    void listQuery_prefixedWithSchemaName() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomer(orderBy: { customer_id: ASC }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomer", hasSize(5)))
                .expect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void connectionQuery_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerConnection(first: 2) { edges { node { customer_id first_name } } pageInfo { hasNextPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(2)))
                .expect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasNextPage").value(true));
    }

    @Test
    @Order(3)
    void aggregateQuery_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaCustomerAggregate { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaCustomerAggregate.count").isNumber());
    }

    @Test
    @Order(4)
    void introspection_showsPrefixedFieldNames() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __schema { queryType { fields { name } } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("testSchemaCustomer")))
                .expect(jsonPath("$.data.__schema.queryType.fields[*].name", not(hasItem("customer"))));
    }

    @Test
    @Order(5)
    void unprefixedFieldName_returnsError() throws Exception {
        http.post("/test-proj/graphql", graphql("{ customer { customer_id } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.errors").exists());
    }

    @Test
    @Order(6)
    void fkTraversal_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ testSchemaOrders(orderBy: { order_id: ASC }) { order_id testSchemaCustomerId { first_name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.testSchemaOrders[0].testSchemaCustomerId.first_name").exists());
    }

    @Test
    @Order(7)
    void createMutation_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("mutation { createTestSchemaCustomer(input: { first_name: \"New\", last_name: \"User\" }) { customer_id first_name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.createTestSchemaCustomer.first_name").value("New"));
    }
}
