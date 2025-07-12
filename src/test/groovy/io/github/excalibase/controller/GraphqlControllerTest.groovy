package io.github.excalibase.controller

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Specification
import spock.lang.Shared

import java.sql.*

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
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

    def "should handle complex nested AND/OR conditions"() {
        given: "a GraphQL query with complex nested conditions"
        def query = '''
        {
            customer(or: [
                { customer_id: { gte: 1, lte: 3 }, first_name: { startsWith: "M" } },
                { customer_id: { gte: 10 }, email: { isNotNull: true } }
            ]) {
                customer_id
                first_name
                last_name
                email
            }
        }
        '''

        when: "sending complex nested query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return successful response with complex filtering"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle case-sensitive vs case-insensitive string operations"() {
        given: "a GraphQL query testing case sensitivity"
        def query = '''
        {
            customer(where: { first_name: { ilike: "mary" } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending case sensitivity test query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return results for case insensitive search"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle empty result sets"() {
        given: "a GraphQL query that should return no results"
        def query = '''
        {
            customer(where: { customer_id: { eq: 99999 } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending query with no matching results"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return empty array"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(0))
    }

    def "should handle boundary value testing for numeric fields"() {
        given: "a GraphQL query testing boundary values"
        def query = '''
        {
            customer(where: { customer_id: { gte: 1, lte: 12 } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending boundary value query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle boundary values correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(12))
    }

    def "should handle large IN arrays"() {
        given: "a GraphQL query with large IN array"
        def query = '''
        {
            customer(where: { 
                customer_id: { 
                    in: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 100, 200, 300] 
                } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending large IN array query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle large arrays efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(12)) // Only 12 exist
    }

    def "should handle NOT IN operations using negation"() {
        given: "a GraphQL query simulating NOT IN with negation"
        def query = '''
        {
            customer(where: { 
                customer_id: { 
                    notIn: [1, 2, 3] 
                } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending NOT IN simulation query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should exclude specified values"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(9)) // 12 total - 3 excluded
    }

    def "should handle multiple field filtering simultaneously"() {
        given: "a GraphQL query filtering multiple fields"
        def query = '''
        {
            customer(where: { 
                customer_id: { gte: 1, lte: 5 },
                first_name: { startsWith: "M" },
                email: { isNotNull: true },
                create_date: { gte: "2006-01-01" }
            }) {
                customer_id
                first_name
                last_name
                email
                create_date
            }
        }
        '''

        when: "sending multi-field filter query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should apply all filters correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle edge cases with special characters in strings"() {
        given: "a GraphQL query with special characters"
        def query = '''
        {
            customer(where: { 
                email: { 
                    contains: "@",
                    endsWith: ".com"
                }
            }) {
                customer_id
                first_name
                email
            }
        }
        '''

        when: "sending special character query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle special characters correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle date range queries across different months"() {
        given: "a GraphQL query spanning multiple months"
        def query = '''
        {
            customer(where: { 
                create_date: { 
                    gte: "2006-02-01", 
                    lt: "2007-02-01" 
                } 
            }) {
                customer_id
                first_name
                create_date
            }
        }
        '''

        when: "sending date range query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should filter date ranges correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle timestamp precision edge cases"() {
        given: "a GraphQL query testing timestamp precision"
        def query = '''
        {
            customer(where: { 
                last_update: { eq: "2013-05-26 14:49:45" } 
            }) {
                customer_id
                last_update
            }
        }
        '''

        when: "sending timestamp precision query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle timestamp precision correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle combination of where and or clauses"() {
        given: "a GraphQL query combining where and or"
        def query = '''
        {
            customer(
                where: { email: { isNotNull: true } },
                or: [
                    { customer_id: { lt: 3 } },
                    { customer_id: { gt: 10 } }
                ]
            ) {
                customer_id
                first_name
                email
            }
        }
        '''

        when: "sending combined where/or query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should combine where and or clauses correctly"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should handle performance with complex queries"() {
        given: "a complex GraphQL query for performance testing"
        def query = '''
        {
            customer(or: [
                { 
                    customer_id: { in: [1, 2, 3, 4, 5] },
                    first_name: { ilike: "%M%" }
                },
                {
                    email: { contains: "example" },
                    create_date: { gte: "2006-01-01" }
                },
                {
                    last_name: { endsWith: "SON" },
                    last_update: { lt: "2014-01-01" }
                }
            ]) {
                customer_id
                first_name
                last_name
                email
                create_date
                last_update
            }
        }
        '''

        when: "sending performance test query"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should complete within reasonable time"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())

        and: "should complete within 5 seconds"
        (endTime - startTime) < 5000
    }

    def "should validate input parameter types"() {
        given: "a GraphQL query with wrong parameter types"
        def query = '''
        {
            customer(where: { customer_id: { eq: "not-a-number" } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending invalid type query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return type validation error"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
    }

    def "should handle SQL injection prevention"() {
        given: "a GraphQL query with potential SQL injection"
        def query = '''
        {
            customer(where: { 
                first_name: { 
                    eq: "'; DROP TABLE customer; --" 
                } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending potential SQL injection query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should safely handle malicious input"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(0))
    }
}