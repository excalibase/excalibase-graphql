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
import spock.lang.Ignore
import spock.lang.Specification
import spock.lang.Shared

import java.sql.*
import java.util.concurrent.*

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Testcontainers
@Ignore
class GraphqlPerformanceTest extends Specification {

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
        setupLargeDataset()
    }

    def cleanupSpec() {
        postgres.stop()
    }

    def setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private static void setupLargeDataset() {
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

            // Create customer table
            statement.execute("""
                CREATE TABLE IF NOT EXISTS customer (
                    customer_id SERIAL PRIMARY KEY,
                    first_name VARCHAR(45) NOT NULL,
                    last_name VARCHAR(45) NOT NULL,
                    email VARCHAR(50),
                    create_date DATE NOT NULL DEFAULT CURRENT_DATE,
                    last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    activebool BOOLEAN DEFAULT true
                );
            """)

            // Insert 1000 test records for performance testing
            for (int i = 1; i <= 1000; i++) {
                statement.execute("""
                    INSERT INTO customer (customer_id, first_name, last_name, email, create_date, last_update, activebool) VALUES
                    (${i}, 'FirstName${i}', 'LastName${i}', 'user${i}@example.com', 
                     '2006-02-14'::date + interval '${i} days', 
                     '2013-05-26 14:49:45'::timestamp + interval '${i} seconds',
                     ${i % 2 == 0});
                """)
            }

            System.out.println("Large dataset setup completed: 1000 records")
        } catch (Exception e) {
            System.err.println("Error setting up large dataset: " + e.getMessage())
            e.printStackTrace()
        }
    }

    def "should handle large result sets efficiently"() {
        given: "a GraphQL query returning many results"
        def query = '''
        {
            customer(where: { customer_id: { gte: 1, lte: 500 } }) {
                customer_id
                first_name
                last_name
                email
                create_date
                last_update
                activebool
            }
        }
        '''

        when: "sending large result query"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should return results efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(500))

        and: "should complete within 600ms"
        (endTime - startTime) < 600
    }

    def "should handle concurrent requests efficiently"() {
        given: "multiple concurrent GraphQL queries"
        def query = '''
        {
            customer(where: { customer_id: { gte: 1, lte: 100 } }) {
                customer_id
                first_name
                email
            }
        }
        '''

        when: "sending 20 concurrent requests"
        def executor = Executors.newFixedThreadPool(20)
        def startTime = System.currentTimeMillis()

        def futures = (1..20).collect { i ->
            CompletableFuture.supplyAsync({
                return mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
            }, executor)
        }

        def results = futures.collect { it.get() }
        def endTime = System.currentTimeMillis()
        executor.shutdown()

        then: "all requests should complete successfully"
        results.each { result ->
            result.andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath('$.data.customer').isArray())
                    .andExpect(jsonPath('$.data.customer.length()').value(100))
        }

        and: "should complete within 200ms"
        (endTime - startTime) < 200
    }

    def "should handle complex filtering with large datasets"() {
        given: "a complex GraphQL query on large dataset"
        def query = '''
        {
            customer(or: [
                { 
                    customer_id: { gte: 1, lte: 100 },
                    activebool: { eq: true },
                    first_name: { startsWith: "FirstName1" }
                },
                {
                    customer_id: { gte: 500, lte: 600 },
                    email: { contains: "example.com" },
                    create_date: { gte: "2006-06-01" }
                },
                {
                    customer_id: { gte: 900 },
                    last_name: { endsWith: "0" },
                    activebool: { eq: false }
                }
            ]) {
                customer_id
                first_name
                last_name
                email
                create_date
                activebool
            }
        }
        '''

        when: "sending complex query on large dataset"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should handle complex filtering efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())

        and: "should complete within 50ms"
        (endTime - startTime) < 50
    }

    def "should handle pagination performance on large datasets"() {
        given: "a limited GraphQL query on large dataset (simulating pagination)"
        def query = '''
        {
            customer(where: { 
                customer_id: { gte: 1, lte: 50 },
                activebool: { eq: true } 
            }) {
                customer_id
                first_name
                last_name
                email
            }
        }
        '''

        when: "sending limited query on large dataset"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should handle limited queries efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())

        and: "should complete within 50ms"
        (endTime - startTime) < 50

        and: "should return reasonable number of results"
        def responseContent = result.andReturn().response.contentAsString
        println("Pagination test response length: ${responseContent.length()}")
    }

    def "should handle memory usage with large IN arrays"() {
        given: "a GraphQL query with very large IN array"
        def largeArray = (1..1000).join(", ")
        def query = """
        {
            customer(where: { 
                customer_id: { 
                    in: [${largeArray}] 
                } 
            }) {
                customer_id
                first_name
            }
        }
        """

        when: "sending query with large IN array"
        def startTime = System.currentTimeMillis()
        def result = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
        def endTime = System.currentTimeMillis()

        then: "should handle large IN arrays efficiently"
        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath('$.data.customer').isArray())
                .andExpect(jsonPath('$.data.customer.length()').value(1000))

        and: "should complete within 200ms"
        (endTime - startTime) < 200
    }

    def "should handle stress test with rapid sequential requests"() {
        given: "rapid sequential GraphQL queries"
        def query = '''
        {
            customer(where: { customer_id: { gte: 1, lte: 10 } }) {
                customer_id
                first_name
            }
        }
        '''

        when: "sending 100 rapid sequential requests"
        def startTime = System.currentTimeMillis()
        def results = []

        (1..100).each { i ->
            def result = mockMvc.perform(post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
            results << result
        }

        def endTime = System.currentTimeMillis()

        then: "all requests should complete successfully"
        results.each { result ->
            result.andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath('$.data.customer').isArray())
                    .andExpect(jsonPath('$.data.customer.length()').value(10))
        }

        and: "should complete within 800ms"
        (endTime - startTime) < 800
    }
} 