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
 * E2E test verifying per-table schema metadata is correctly set during introspection
 * and used when compiling SQL. Uses a real PostgreSQL container with a named schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerTableSchemaE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("app.max-rows", () -> 30);
    }

    @Autowired
    private MockMvc mockMvc;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String graphql(String query) throws Exception {
        return mapper.writeValueAsString(Map.of("query", query));
    }

    // === Verify schema-qualified queries work (tables loaded with correct schema) ===

    @Test
    @Order(1)
    void listQuery_usesCorrectSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testSchemaCustomer(orderBy: { customer_id: ASC }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSchemaCustomer", hasSize(5)))
                .andExpect(jsonPath("$.data.testSchemaCustomer[0].first_name").value("Alice"));
    }

    @Test
    @Order(2)
    void connectionQuery_usesCorrectSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testSchemaCustomerConnection(first: 2) { edges { node { customer_id first_name } } pageInfo { hasNextPage } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSchemaCustomerConnection.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.testSchemaCustomerConnection.pageInfo.hasNextPage").value(true));
    }

    @Test
    @Order(3)
    void fkTraversal_usesCorrectSchema() throws Exception {
        // orders has FK to customer — both in test_schema; field name derived from FK column "customer_id" → "testSchemaCustomerId"
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testSchemaOrders(orderBy: { order_id: ASC }) { order_id total_amount testSchemaCustomerId { first_name } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSchemaOrders[0].testSchemaCustomerId.first_name").exists());
    }

    @Test
    @Order(4)
    void createMutation_usesCorrectSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("mutation { createTestSchemaCustomer(input: { first_name: \"Test\", last_name: \"User\", email: \"test@test.com\" }) { customer_id first_name } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTestSchemaCustomer.first_name").value("Test"));
    }

    @Test
    @Order(5)
    void introspection_returnsSchemaWithTables() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ __schema { queryType { fields { name } } } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.fields[*].name", hasItem("testSchemaCustomer")));
    }

    @Test
    @Order(6)
    void aggregateQuery_usesCorrectSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphql("{ testSchemaCustomerAggregate { count } }")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.testSchemaCustomerAggregate.count").isNumber());
    }
}
