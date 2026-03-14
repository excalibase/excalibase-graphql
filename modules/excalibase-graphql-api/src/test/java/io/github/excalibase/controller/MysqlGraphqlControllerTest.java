package io.github.excalibase.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end GraphQL API tests for the MySQL backend.
 *
 * <p>Boots the full Spring Boot application with a MySQL testcontainer and exercises
 * the {@code /graphql} HTTP endpoint for queries, mutations, filtering, pagination,
 * and relationship resolution.</p>
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("mysql-test")
@Testcontainers
class MysqlGraphqlControllerTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("mysql-test-init.sql");

    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.allowed-schema", () -> "testdb");
        registry.add("app.database-type", () -> "MySQL");
    }

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        insertTestData();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanTestData();
    }

    // ─── Test data management ──────────────────────────────────────────────────
    // Tables are created by mysql-test-init.sql (runs at container start, before Spring context).
    // Each test only inserts/deletes rows so the GraphQL schema stays stable across tests.

    private static void insertTestData() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO customer (first_name, last_name, email) VALUES
                    ('MARY',      'SMITH',    'mary@example.com'),
                    ('PATRICIA',  'JOHNSON',  'patricia@example.com'),
                    ('LINDA',     'WILLIAMS', null),
                    ('BARBARA',   'JONES',    'barbara@example.com'),
                    ('ELIZABETH', 'BROWN',    'elizabeth@example.com')
                    """);

            stmt.execute("""
                    INSERT INTO orders (customer_id, total, status) VALUES
                    (1, 99.99,  'completed'),
                    (1, 149.50, 'completed'),
                    (2, 49.99,  'pending'),
                    (3, 299.00, 'shipped')
                    """);
        }
    }

    private static void cleanTestData() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
            stmt.execute("TRUNCATE TABLE orders");
            stmt.execute("TRUNCATE TABLE customer");
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    // ─── Query tests ──────────────────────────────────────────────────────────

    @Test
    void shouldListAllCustomers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer { customer_id first_name last_name email } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(5)));
    }

    @Test
    void shouldFilterCustomersByFirstName() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(where: { first_name: { eq: \\"MARY\\" } }) { customer_id first_name } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(1)))
                .andExpect(jsonPath("$.data.customer[0].first_name").value("MARY"));
    }

    @Test
    void shouldLimitResults() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(limit: 2) { customer_id first_name } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(2)));
    }

    @Test
    void shouldOffsetResults() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(limit: 10, offset: 3) { customer_id first_name } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(2)));
    }

    @Test
    void shouldOrderByColumn() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(orderBy: { first_name: \\"ASC\\" }) { first_name } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("BARBARA"));
    }

    @Test
    void shouldReturnConnectionWithPageInfo() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customerConnection(first: 2) { edges { node { customer_id first_name } cursor } pageInfo { hasNextPage hasPreviousPage } totalCount } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerConnection.edges").isArray())
                .andExpect(jsonPath("$.data.customerConnection.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.customerConnection.pageInfo.hasNextPage").value(true))
                .andExpect(jsonPath("$.data.customerConnection.totalCount").value(5));
    }

    @Test
    void shouldReturnAggregateCount() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer_aggregate { count } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer_aggregate.count").value(5));
    }

    @Test
    void shouldReturnAggregateOnNumericColumn() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ orders_aggregate { count sum avg } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders_aggregate.count").value(4));
    }

    // ─── Mutation tests ───────────────────────────────────────────────────────

    @Test
    void shouldCreateCustomer() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "mutation { createCustomer(input: { first_name: \\"John\\", last_name: \\"Doe\\", email: \\"john.doe@example.com\\" }) { customer_id first_name last_name email } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createCustomer.customer_id").isNumber())
                .andExpect(jsonPath("$.data.createCustomer.first_name").value("John"))
                .andExpect(jsonPath("$.data.createCustomer.last_name").value("Doe"))
                .andExpect(jsonPath("$.data.createCustomer.email").value("john.doe@example.com"));
    }

    @Test
    void shouldUpdateCustomer() throws Exception {
        // First create
        String createResponse = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "mutation { createCustomer(input: { first_name: \\"Jane\\", last_name: \\"Smith\\" }) { customer_id } }"}
                                """))
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createResponse).at("/data/createCustomer/customer_id").numberValue()).longValue();

        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"query": "mutation { updateCustomer(id: %d, input: { first_name: \\"Janet\\", last_name: \\"Doe\\" }) { customer_id first_name last_name } }"}
                                """, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updateCustomer.customer_id").value(id))
                .andExpect(jsonPath("$.data.updateCustomer.first_name").value("Janet"))
                .andExpect(jsonPath("$.data.updateCustomer.last_name").value("Doe"));
    }

    @Test
    void shouldDeleteCustomer() throws Exception {
        // First create
        String createResponse = mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "mutation { createCustomer(input: { first_name: \\"ToDelete\\", last_name: \\"User\\" }) { customer_id } }"}
                                """))
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createResponse).at("/data/createCustomer/customer_id").numberValue()).longValue();

        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"query": "mutation { deleteCustomer(id: %d) { customer_id first_name } }"}
                                """, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleteCustomer.customer_id").value(id))
                .andExpect(jsonPath("$.data.deleteCustomer.first_name").value("ToDelete"));

        // Verify deleted
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {"query": "{ customer(where: { customer_id: { eq: %d } }) { customer_id } }"}
                                """, id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(0)));
    }

    @Test
    void shouldBulkCreateCustomers() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "mutation { createManyCustomers(input: [{ first_name: \\"Bulk1\\", last_name: \\"A\\" }, { first_name: \\"Bulk2\\", last_name: \\"B\\" }, { first_name: \\"Bulk3\\", last_name: \\"C\\" }]) { customer_id first_name } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createManyCustomers").isArray())
                .andExpect(jsonPath("$.data.createManyCustomers", hasSize(3)))
                .andExpect(jsonPath("$.data.createManyCustomers[0].first_name").value("Bulk1"))
                .andExpect(jsonPath("$.data.createManyCustomers[1].first_name").value("Bulk2"))
                .andExpect(jsonPath("$.data.createManyCustomers[2].first_name").value("Bulk3"));
    }

    // ─── Relationship tests ───────────────────────────────────────────────────

    @Test
    void shouldListOrders() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ orders { order_id customer_id total status } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders").isArray())
                .andExpect(jsonPath("$.data.orders", hasSize(4)));
    }

    @Test
    void shouldFilterOrdersByStatus() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ orders(where: { status: { eq: \\"completed\\" } }) { order_id status } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders").isArray())
                .andExpect(jsonPath("$.data.orders", hasSize(2)));
    }

    // ─── Schema introspection ─────────────────────────────────────────────────

    @Test
    void shouldExposeSchemaIntrospection() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ __schema { queryType { name } mutationType { name } } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
                .andExpect(jsonPath("$.data.__schema.mutationType.name").value("Mutation"));
    }

    @Test
    void shouldExposeCustomerTypeInSchema() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ __type(name: \\"customer\\") { name fields { name } } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.__type.name").value("customer"))
                .andExpect(jsonPath("$.data.__type.fields").isArray());
    }

    @Test
    void shouldHandleNullEmailGracefully() throws Exception {
        // LINDA has null email
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(where: { first_name: { eq: \\"LINDA\\" } }) { customer_id first_name email } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer[0].first_name").value("LINDA"));
    }

    @Test
    void shouldReturnEmptyListForNonExistentFilter() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "{ customer(where: { first_name: { eq: \\"NOBODY\\" } }) { customer_id } }"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customer").isArray())
                .andExpect(jsonPath("$.data.customer", hasSize(0)));
    }
}
