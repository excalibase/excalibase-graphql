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
 * E2E test for multi-schema merge support.
 * Two schemas (schema_a, schema_b) loaded into one GraphQL API with prefixed type names.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiSchemaE2ETest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-multischema.sql");

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

    // === Prefixed list queries ===

    @Test
    @Order(1)
    void listSchemaAUsers_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ schemaAUsers { user_id name } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.schemaAUsers", hasSize(2)))
                .expect(jsonPath("$.data.schemaAUsers[0].name").value("Alice"));
    }

    @Test
    @Order(2)
    void listSchemaBOrders_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ schemaBOrders { order_id amount } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.schemaBOrders", hasSize(3)));
    }

    // === Introspection shows prefixed types ===

    @Test
    @Order(3)
    void introspection_showsPrefixedTypes() throws Exception {
        http.post("/test-proj/graphql", graphql("{ __schema { queryType { fields { name } } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("schemaAUsers")))
                .expect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("schemaBOrders")));
    }

    // === Cross-schema FK traversal ===

    @Test
    @Order(4)
    void crossSchemaFk_ordersToUsers() throws Exception {
        http.post("/test-proj/graphql", graphql("{ schemaBOrders(orderBy: { order_id: ASC }) { order_id amount schemaBUserId { name } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.schemaBOrders[0].schemaBUserId.name").value("Alice"));
    }

    // === Connection query with prefix ===

    @Test
    @Order(5)
    void connectionQuery_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ schemaAUsersConnection(first: 1) { edges { node { user_id name } } pageInfo { hasNextPage } } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.schemaAUsersConnection.edges", hasSize(1)))
                .expect(jsonPath("$.data.schemaAUsersConnection.pageInfo.hasNextPage").value(true));
    }

    // === Aggregate query with prefix ===

    @Test
    @Order(6)
    void aggregateQuery_prefixed() throws Exception {
        http.post("/test-proj/graphql", graphql("{ schemaBOrdersAggregate { count } }"), "Content-Type", "application/json")
                .expect(status().isOk())
                .expect(jsonPath("$.data.schemaBOrdersAggregate.count").value(3));
    }
}
