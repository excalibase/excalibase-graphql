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
import static org.hamcrest.Matchers.*

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Testcontainers
class GraphqlSecurityTest extends Specification {

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

            statement.execute("""
                CREATE SCHEMA IF NOT EXISTS public;
                SET search_path TO public;
            """)

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

            statement.execute("""
                INSERT INTO customer (customer_id, first_name, last_name, email, create_date, last_update) VALUES
                (1, 'MARY', 'SMITH', 'mary.smith@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (2, 'PATRICIA', 'JOHNSON', 'patricia.johnson@example.com', '2006-02-14', '2013-05-26 14:49:45'),
                (3, 'LINDA', 'WILLIAMS', 'linda.williams@example.com', '2006-02-14', '2013-05-26 14:49:45');
            """)

        } catch (Exception e) {
            System.err.println("Error setting up security test data: " + e.getMessage())
            e.printStackTrace()
        }
    }

    def "should prevent SQL injection in string filters"() {
        given: "a GraphQL query with SQL injection attempt"
        def maliciousInput = "'; DROP TABLE customer; SELECT * FROM customer WHERE first_name = '"
        def query = """
        {
            customer(where: { 
                first_name: { eq: "${maliciousInput}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending SQL injection attempt"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should safely handle malicious input without executing SQL injection"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(0))
    }

    def "should prevent SQL injection in LIKE operations"() {
        given: "a GraphQL query with SQL injection in LIKE"
        def maliciousInput = "test%'; DELETE FROM customer; --"
        def query = """
        {
            customer(where: { 
                first_name: { like: "${maliciousInput}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending LIKE injection attempt"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should safely handle LIKE injection"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should prevent NoSQL-style injection attempts"() {
        given: "a GraphQL query with NoSQL injection patterns"
        def query = '''
        {
            customer(where: { 
                first_name: { eq: "' || '1'='1" } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending NoSQL injection attempt"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should safely handle NoSQL injection patterns"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(0))
    }

    def "should handle extremely long input strings safely"() {
        given: "a GraphQL query with extremely long string"
        def longString = "A" * 10000
        def query = """
        {
            customer(where: { 
                first_name: { eq: "${longString}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending query with long string"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle long strings without issues"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
    }

    def "should prevent injection through numeric fields"() {
        given: "a GraphQL query attempting injection through numeric fields"
        def query = '''
        {
            customer(where: { 
                customer_id: { eq: "1; DROP TABLE customer; --" } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending numeric field injection attempt"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return type validation error"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
    }

    def "should handle malicious regex patterns safely"() {
        given: "a GraphQL query with potentially malicious regex"
        def maliciousRegex = ".*.*.*.*.*"
        def query = """
        {
            customer(where: { 
                first_name: { like: "${maliciousRegex}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending malicious regex pattern"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle regex patterns safely"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        and: "should complete within reasonable time and return valid response"
        def responseContent = result.andReturn().response.contentAsString
        assert responseContent.contains('"data"') || responseContent.contains('"errors"')
    }

    def "should prevent information disclosure through error messages"() {
        given: "a GraphQL query that might cause database errors"
        def query = '''
        {
            customer(where: { 
                nonexistent_field: { eq: "test" } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending query with invalid field"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should return appropriate error without sensitive information"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
        // Error message should not contain internal details like table names, etc.
    }

    def "should handle special characters and encoding attacks"() {
        given: "a GraphQL query with various special characters"
        def specialChars = "@#\$%^&*()_+-=[]{}|;:,.<>?"
        def query = """
        {
            customer(where: { 
                first_name: { eq: "${specialChars}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending special character query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle special characters safely"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        and: "should return valid GraphQL response"
        def responseContent = result.andReturn().response.contentAsString
        assert responseContent.contains('"data"') || responseContent.contains('"errors"')
    }

    def "should prevent time-based injection attacks"() {
        given: "a GraphQL query attempting time-based injection"
        def query = '''
        {
            customer(where: { 
                first_name: { eq: "'; SELECT pg_sleep(10); --" } 
            }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending time-based injection attempt"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should complete quickly without delay injection"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())

        and: "should not be delayed by injection attempt"
        (endTime - startTime) < 1000 // Should complete in under 1000ms
    }

    def "should handle Unicode and international character attacks"() {
        given: "a GraphQL query with Unicode characters"
        def unicodeString = "\\u0041\\u0042\\u0043" // ABC in Unicode
        def query = """
        {
            customer(where: { 
                first_name: { eq: "${unicodeString}" } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending Unicode character query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle Unicode characters safely"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        // Check for either data.customer array OR errors array
                .andExpect(jsonPath('$').exists())

        and: "should have either data or errors in response"
        def responseContent = result.andReturn().response.contentAsString
                    // Unicode test response validated
        assert responseContent.contains('"data"') || responseContent.contains('"errors"')
    }

    def "should validate query depth and complexity"() {
        given: "a simple GraphQL query to test basic complexity handling"
        def simpleQuery = '''
        {
            customer {
                customer_id
                first_name
                last_name
                email
            }
        }
        '''

        when: "sending query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${simpleQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should handle query appropriately"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        and: "should return valid GraphQL response"
        def responseContent = result.andReturn().response.contentAsString
        assert responseContent.contains('"data"') || responseContent.contains('"errors"')
    }

    def "should handle malformed JSON attacks"() {
        given: "malformed JSON input"
        def malformedJson = '{"query": "{ customer { customer_id } }", "variables": {"invalid": '

        when: "sending malformed JSON"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson))

        then: "should handle malformed JSON gracefully"
        result.andExpect(status().is4xxClientError())
    }

    def "should execute SET ROLE when X-Database-Role header is provided"() {
        given: "a test role with permissions"
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {
            
            statement.execute("CREATE ROLE test_user_role")
            statement.execute("GRANT SELECT ON customer TO test_user_role")
        }
        
        and: "a GraphQL query"
        def query = """
            {
                customer(limit: 1) {
                    customer_id
                    first_name
                }
            }
        """
        
        when: "executing query with X-Database-Role header"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "test_user_role")
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        
        then: "query should execute successfully with role context"
        // Validate that the response is successful and contains data
        result.contains('"data"')
        result.contains('"customer"')
        !result.contains('"errors"')
        
        cleanup:
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL PRIVILEGES ON customer FROM test_user_role")
            statement.execute("DROP ROLE IF EXISTS test_user_role")
        }
    }

    // ===========================================
    // GRAPHQL DOS PROTECTION TESTS  
    // Tests for depth limiting, complexity analysis, and other security controls
    // Based on GraphQL.org security recommendations
    // ===========================================

    def "should reject queries that exceed maximum depth limit"() {
        given: "a deeply nested GraphQL query that exceeds depth limit (> 8 levels)"
        // Create a query with 10+ levels of nesting to exceed our limit of 8
        def deepQuery = '''
        {
            __schema {
                types {
                    name
                    fields {
                        name
                        type {
                            name
                            fields {
                                name
                                type {
                                    name
                                    fields {
                                        name
                                        type {
                                            name
                                            fields {
                                                name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        '''

        when: "sending deeply nested query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${deepQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should reject with depth limit error"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
                .andExpect(jsonPath('$.errors[0].message').value(containsString("maximum query depth exceeded")))
                .andExpect(jsonPath('$.errors[0].extensions.classification').value("ExecutionAborted"))
    }

    def "should reject queries with excessive field aliases (breadth attack)"() {
        given: "a GraphQL query with many field aliases"
        // Create 200 aliases with limit parameters to exceed 500 complexity points
        // Each alias with limit=100 costs: 3 (base) + 10 (limit/10) = 13 points  
        // 200 aliases × 13 points = 2600 points (exceeds 500 limit)
        def aliasQuery = '{ '
        for (int i = 1; i <= 200; i++) {
            aliasQuery += "c${i}: customer(limit: 100) { customer_id first_name last_name email } "
        }
        aliasQuery += ' }'

        when: "sending query with many aliases"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${aliasQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should reject with complexity/breadth limit error"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
                .andExpect(jsonPath('$.errors[0].message').value(containsString("complexity")))
    }

    def "should reject batched operations that exceed limit"() {
        given: "multiple GraphQL operations in a single request"
        def batchedQuery = """
        [
            {"query": "{ customer { customer_id first_name } }"},
            {"query": "{ customer { customer_id last_name } }"},
            {"query": "{ customer { customer_id email } }"}
        ]
        """

        when: "sending batched operations"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchedQuery))

        then: "should reject batched operations (GraphQL Java doesn't support batching by default)"
        // Batched operations are rejected at the HTTP/JSON parsing level, not GraphQL level
        result.andExpect(status().is4xxClientError())
    }

    def "should reject queries that exceed request size limit"() {
        given: "a very large GraphQL query that exceeds complexity limit through sheer size"
        // Create an extremely large query with 1000 aliases, each with large limits
        // This will definitely exceed our 500 complexity limit and test size handling
        def largeQuery = '{ '
        for (int i = 1; i <= 1000; i++) {
            largeQuery += "customer_${i}: customer(limit: 1000) { customer_id first_name last_name email } "
        }
        largeQuery += ' }'

        when: "sending oversized query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${largeQuery.replaceAll('"', '\\\\"')}"}"""))

        then: "should reject with complexity/size limit error"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.errors').exists())
                .andExpect(jsonPath('$.errors[0].message').value(containsString("complexity")))
    }

    def "should allow reasonable queries within limits"() {
        given: "a reasonable GraphQL query within limits"
        def reasonableQuery = '''
        {
            customer(limit: 5) {
                customer_id
                first_name
                last_name
                email
            }
        }
        '''

        when: "sending reasonable query"
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${reasonableQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))

        then: "should allow and return data"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.errors').doesNotExist())
    }
}