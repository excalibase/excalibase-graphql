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

/**
 * E2E test for multi-schema merge support.
 * Two schemas (schema_a, schema_b) loaded into one GraphQL API with prefixed type names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiSchemaE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-multischema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("app.database-type", () -> "postgres");
        registry.add("app.max-rows", () -> 30);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === Prefixed list queries ===

    @Test
    @Order(1)
    void listSchemaAUsers_prefixed() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ schemaAUsers { user_id name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaAUsers", hasSize(2)))
                .andExpect(jsonPath("$.data.schemaAUsers[0].name").value("Alice"));
    }

    @Test
    @Order(2)
    void listSchemaBOrders_prefixed() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ schemaBOrders { order_id amount } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaBOrders", hasSize(3)));
    }

    // === Introspection shows prefixed types ===

    @Test
    @Order(3)
    void introspection_showsPrefixedTypes() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { queryType { fields { name } } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("schemaAUsers")))
                .andExpect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("schemaBOrders")));
    }

    // === Cross-schema FK traversal ===

    @Test
    @Order(4)
    void crossSchemaFk_ordersToUsers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ schemaBOrders(orderBy: { order_id: ASC }) { order_id amount schemaBUserId { name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaBOrders[0].schemaBUserId.name").value("Alice"));
    }

    // === Connection query with prefix ===

    @Test
    @Order(5)
    void connectionQuery_prefixed() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ schemaAUsersConnection(first: 1) { edges { node { user_id name } } pageInfo { hasNextPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaAUsersConnection.edges", hasSize(1)))
                .andExpect(jsonPath("$.data.schemaAUsersConnection.pageInfo.hasNextPage").value(true));
    }

    // === Aggregate query with prefix ===

    @Test
    @Order(6)
    void aggregateQuery_prefixed() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ schemaBOrdersAggregate { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaBOrdersAggregate.count").value(3));
    }
}
