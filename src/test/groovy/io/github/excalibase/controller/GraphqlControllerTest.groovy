package io.github.excalibase.controller

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification
import spock.lang.Shared

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Testcontainers
class GraphqlControllerTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")

    @Autowired
    WebApplicationContext webApplicationContext

    MockMvc mockMvc

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.datasource.hikari.schema", { "public" })
        registry.add("app.allowed-schema", { "public" })
        registry.add("app.database-type", { "postgres" })
    }

    def setupSpec() {
        postgres.start()
        setupTestData()
    }

    def cleanupSpec() {
        postgres.stop()
    }

    def setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private static void setupTestData() {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {

            // Create test schema and tables
            statement.execute("""
                CREATE SCHEMA IF NOT EXISTS public;
                SET search_path TO public;
            """)

            // Create customer table with sample data
            statement.execute("""
                CREATE TABLE IF NOT EXISTS customer (
                    customer_id SERIAL PRIMARY KEY,
                    first_name VARCHAR(45) NOT NULL,
                    last_name VARCHAR(45) NOT NULL,
                    email VARCHAR(50),
                    create_date DATE NOT NULL DEFAULT CURRENT_DATE,
                    last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
            """)

            // Insert sample data
            statement.execute("""
                INSERT INTO customer (customer_id, first_name, last_name, email, create_date, last_update) VALUES
                (1, 'MARY', 'SMITH', 'mary.smith@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (2, 'PATRICIA', 'JOHNSON', 'patricia.johnson@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (3, 'LINDA', 'WILLIAMS', 'linda.williams@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (4, 'BARBARA', 'JONES', 'barbara.jones@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (5, 'ELIZABETH', 'BROWN', 'elizabeth.brown@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (6, 'JENNIFER', 'DAVIS', null, '2006-02-15', '2013-05-26 14:49:45'),
                (7, 'MARIA', 'MILLER', null, '2006-02-15', '2013-05-26 14:49:45'),
                (8, 'SUSAN', 'WILSON', 'susan.wilson@example.com', '2006-02-15', '2013-05-26 14:49:45'),
                (9, 'MARGARET', 'MOORE', 'margaret.moore@example.com', '2006-02-15', '2013-05-26 14:49:45'),
                (10, 'DOROTHY', 'TAYLOR', 'dorothy.taylor@example.com', '2006-02-15', '2013-05-26 14:49:45');
            """)

            // Add some customers with different dates for testing
            statement.execute("""
                INSERT INTO customer (customer_id, first_name, last_name, email, create_date, last_update) VALUES
                (11, 'MARY', 'SMITHSON', 'mary.smithson@example.com', '2007-01-01', '2013-05-26 14:49:45'),
                (12, 'JOHN', 'SMITH', 'john.smith@example.com', '2007-01-01', '2013-05-26 14:49:45');
            """)

            System.out.println("Test data setup completed successfully")
        } catch (Exception e) {
            System.err.println("Error setting up test data: " + e.getMessage())
            e.printStackTrace()
        }
    }

    def "should return GraphQL schema with enhanced filter types"() {
        given: "a GraphQL introspection query"
        def introspectionQuery = '''
        {
            __schema {
                queryType {
                    fields {
                        name
                        args {
                            name
                            type {
                                name
                                inputFields {
                                    name
                                    type {
                                        name
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        '''

        when: "sending introspection query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${introspectionQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with filter types"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.__schema').exists())
    }

    def "should handle basic date equality filtering"() {
        given: "a GraphQL query with date equality filter"
        def query = '''
        {
            customer(where: { create_date: { eq: "2006-02-14" } }) {
                customer_id
                first_name
                last_name
                create_date
            }
        }
        '''

        when: "sending date equality query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with filtered customers"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle timestamp range filtering"() {
        given: "a GraphQL query with timestamp range filter"
        def query = '''
        {
            customer(where: { 
                last_update: { 
                    gte: "2013-05-26 14:49:45", 
                    lt: "2013-05-26 14:49:46" 
                } 
            }) {
                customer_id
                first_name
                last_name
                last_update
            }
        }
        '''

        when: "sending timestamp range query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with filtered customers"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle OR operations with enhanced filters"() {
        given: "a GraphQL query with OR conditions"
        def query = '''
        {
            customer(or: [
                { customer_id: { eq: 1 } }, 
                { customer_id: { eq: 2 } }, 
                { customer_id: { eq: 3 } }
            ]) {
                customer_id
                first_name
                last_name
            }
        }
        '''

        when: "sending OR query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with multiple customers"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(3))
    }

    def "should handle integer IN operations"() {
        given: "a GraphQL query with IN filter"
        def query = '''
        {
            customer(where: { customer_id: { in: [1, 2, 3, 4, 5] } }) {
                customer_id
                first_name
                last_name
            }
        }
        '''

        when: "sending IN query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with customers in the list"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(5))
    }

    def "should handle null operations"() {
        given: "a GraphQL query with null checks"
        def query = '''
        {
            customer(where: { email: { isNotNull: true } }) {
                customer_id
                first_name
                last_name
                email
            }
        }
        '''

        when: "sending null check query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with non-null email customers"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle string filtering operations"() {
        given: "a GraphQL query with string filters"
        def query = '''
        {
            customer(where: { 
                first_name: { startsWith: "MARY" },
                last_name: { contains: "SMITH" }
            }) {
                customer_id
                first_name
                last_name
            }
        }
        '''

        when: "sending string filter query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with matching customers"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle connection queries with enhanced filtering"() {
        given: "a GraphQL connection query with enhanced filters"
        def query = '''
        {
            customerConnection(
                first: 10,
                where: { create_date: { gte: "2006-01-01" } }
            ) {
                edges {
                    node {
                        customer_id
                        first_name
                        last_name
                        create_date
                    }
                }
                pageInfo {
                    hasNextPage
                    hasPreviousPage
                }
            }
        }
        '''

        when: "sending connection query with filters"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with connection structure"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customerConnection.edges').isArray())
                .andExpect(jsonPath('$.data.customerConnection.pageInfo').exists())
    }

    def "should handle legacy filter syntax for backward compatibility"() {
        given: "a GraphQL query with legacy filter syntax"
        def query = '''
        { 
            customer
            ( where: { customer_id: { eq: 1 } }) 
            { customer_id first_name last_name } }
        '''

        when: "sending legacy filter query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response maintaining backward compatibility"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle error cases gracefully"() {
        given: "a GraphQL query with invalid date format"
        def query = '''
        {
            customer(where: { create_date: { eq: "invalid-date-format" } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending invalid date query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return error response with proper error handling"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
    }
}