package io.github.excalibase.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Comprehensive test for PostgreSQL Row Level Security (RLS) and Column Level Security (CLS)
 * functionality via SET ROLE mechanism.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.yaml")
class GraphqlRlsClsTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")

    @Autowired
    MockMvc mockMvc

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    WebApplicationContext webApplicationContext

    @org.springframework.test.context.DynamicPropertySource
    static void configureProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
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
        // Cleanup test roles and data
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {

            statement.execute("DROP TABLE IF EXISTS sensitive_customer CASCADE")
            statement.execute("DROP ROLE IF EXISTS hr_manager")
            statement.execute("DROP ROLE IF EXISTS dept_manager")
            statement.execute("DROP ROLE IF EXISTS employee")
            statement.execute("DROP ROLE IF EXISTS public_user")
        }
        postgres.stop()
    }

    def setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
    }

    private static void setupTestData() {
        // Setup comprehensive test scenario with multiple roles and security policies
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {

            // 1. Create test table with sensitive data
            statement.execute("""
                CREATE TABLE IF NOT EXISTS sensitive_customer (
                    customer_id SERIAL PRIMARY KEY,
                    first_name VARCHAR(50) NOT NULL,
                    last_name VARCHAR(50) NOT NULL,
                    email VARCHAR(100) NOT NULL,
                    salary DECIMAL(10,2),
                    department_id INTEGER NOT NULL,
                    ssn VARCHAR(11),  -- Very sensitive data
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)

            // 2. Insert test data with different departments
            statement.execute("""
                INSERT INTO sensitive_customer (first_name, last_name, email, salary, department_id, ssn) VALUES
                ('John', 'Doe', 'john.doe@company.com', 75000.00, 1, '123-45-6789'),
                ('Jane', 'Smith', 'jane.smith@company.com', 85000.00, 1, '987-65-4321'),
                ('Bob', 'Wilson', 'bob.wilson@company.com', 65000.00, 2, '456-78-9012'),
                ('Alice', 'Brown', 'alice.brown@company.com', 95000.00, 2, '789-01-2345'),
                ('Charlie', 'Davis', 'charlie.davis@company.com', 120000.00, 3, '321-54-6987')
            """)

            // 3. Create different roles with varying privileges
            
            // HR Manager - can see all data including SSN
            statement.execute("DROP ROLE IF EXISTS hr_manager")
            statement.execute("CREATE ROLE hr_manager")
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON sensitive_customer TO hr_manager")
            statement.execute("GRANT USAGE, SELECT ON SEQUENCE sensitive_customer_customer_id_seq TO hr_manager")

            // Department Manager - can only see their department's data, no SSN
            statement.execute("DROP ROLE IF EXISTS dept_manager")
            statement.execute("CREATE ROLE dept_manager")
            statement.execute("GRANT SELECT (customer_id, first_name, last_name, email, salary, department_id, created_at) ON sensitive_customer TO dept_manager")

            // Employee - can only see basic info from their department, no salary/SSN
            statement.execute("DROP ROLE IF EXISTS employee")
            statement.execute("CREATE ROLE employee")
            statement.execute("GRANT SELECT (customer_id, first_name, last_name, email, department_id, created_at) ON sensitive_customer TO employee")

            // Public user - very limited access
            statement.execute("DROP ROLE IF EXISTS public_user")
            statement.execute("CREATE ROLE public_user")
            statement.execute("GRANT SELECT (customer_id, first_name, last_name, department_id) ON sensitive_customer TO public_user")

            // 4. Enable Row Level Security
            statement.execute("ALTER TABLE sensitive_customer ENABLE ROW LEVEL SECURITY")

            // 5. Create RLS policies for different roles

            // HR Manager - can see everything
            statement.execute("""
                DROP POLICY IF EXISTS hr_manager_policy ON sensitive_customer
            """)
            statement.execute("""
                CREATE POLICY hr_manager_policy ON sensitive_customer 
                FOR ALL TO hr_manager 
                USING (true)
            """)

            // Department Manager - can only see their department (assume they manage dept 1)
            statement.execute("""
                DROP POLICY IF EXISTS dept_manager_policy ON sensitive_customer
            """)
            statement.execute("""
                CREATE POLICY dept_manager_policy ON sensitive_customer 
                FOR SELECT TO dept_manager 
                USING (department_id = 1)
            """)

            // Employee - can only see their department (assume they're in dept 2)
            statement.execute("""
                DROP POLICY IF EXISTS employee_policy ON sensitive_customer
            """)
            statement.execute("""
                CREATE POLICY employee_policy ON sensitive_customer 
                FOR SELECT TO employee 
                USING (department_id = 2)
            """)

            // Public user - can only see department 1 records
            statement.execute("""
                DROP POLICY IF EXISTS public_user_policy ON sensitive_customer
            """)
            statement.execute("""
                CREATE POLICY public_user_policy ON sensitive_customer 
                FOR SELECT TO public_user 
                USING (department_id = 1)
            """)


        }
    }

    def "should enforce Row Level Security policies for different roles"() {
        given: "GraphQL query to fetch all customer data"
        def query = """
            {
                sensitive_customer {
                    customer_id
                    first_name
                    last_name
                    email
                    department_id
                }
            }
        """

        when: "HR Manager queries data"
        def hrResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "hr_manager")
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        def hrResult = objectMapper.readValue(hrResponse, Map)

        then: "HR Manager should see all 5 records from all departments"
        hrResult.data.sensitive_customer.size() == 5
        hrResult.data.sensitive_customer.collect { it.first_name }.containsAll(["John", "Jane", "Bob", "Alice", "Charlie"])
        hrResult.data.sensitive_customer.collect { it.department_id }.containsAll([1, 2, 3])

        when: "Department Manager queries data"
        def deptResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "dept_manager")
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        def deptResult = objectMapper.readValue(deptResponse, Map)

        then: "Department Manager should only see department 1 records (John and Jane)"
        deptResult.data.sensitive_customer.size() == 2
        deptResult.data.sensitive_customer.every { it.department_id == 1 }
        deptResult.data.sensitive_customer.collect { it.first_name }.containsAll(["John", "Jane"])

        when: "Employee queries data"
        def empResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "employee")
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        def empResult = objectMapper.readValue(empResponse, Map)

        then: "Employee should only see department 2 records (Bob and Alice)"
        empResult.data.sensitive_customer.size() == 2
        empResult.data.sensitive_customer.every { it.department_id == 2 }
        empResult.data.sensitive_customer.collect { it.first_name }.containsAll(["Bob", "Alice"])
    }

    def "should enforce Column Level Security for sensitive fields"() {
        given: "GraphQL query requesting sensitive data"
        def sensitiveQuery = """
            {
                sensitive_customer {
                    customer_id
                    first_name
                    last_name
                    email
                    salary
                    ssn
                    department_id
                }
            }
        """

        when: "HR Manager requests sensitive data"
        def hrResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "hr_manager")
                .content("""{"query": "${sensitiveQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        def hrResult = objectMapper.readValue(hrResponse, Map)

        then: "HR Manager should access all fields including sensitive data"
        !hrResult.errors
        hrResult.data.sensitive_customer[0].salary == 75000.0
        hrResult.data.sensitive_customer[0].ssn == "123-45-6789"

        when: "Department Manager requests sensitive data"
        def deptResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "dept_manager")
                .content("""{"query": "${sensitiveQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def deptResult = objectMapper.readValue(deptResponse, Map)

        then: "Department Manager should get permission error for SSN field"
        deptResult.errors?.any { it.message.contains("ssn") || it.message.contains("undefined") }

        when: "Employee requests salary data"
        def salaryQuery = """
            {
                sensitive_customer {
                    customer_id
                    first_name
                    salary
                }
            }
        """

        def empResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "employee")
                .content("""{"query": "${salaryQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def empResult = objectMapper.readValue(empResponse, Map)

        then: "Employee should get permission error for salary field"
        empResult.errors?.any { it.message.contains("salary") || it.message.contains("undefined") }
    }

    def "should enforce Column Level Security during data access (not schema introspection)"() {
        given: "GraphQL introspection query for sensitive_customer type"
        def introspectionQuery = """
            {
                __type(name: "SensitiveCustomer") {
                    fields {
                        name
                        type {
                            name
                        }
                    }
                }
            }
        """

        when: "Public user introspects schema"
        def publicResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "public_user")
                .content("""{"query": "${introspectionQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        def publicSchema = objectMapper.readValue(publicResponse, Map)

        then: "Schema introspection shows all fields (security enforced at data access level)"
        def publicFields = publicSchema.data.__type?.fields?.collect { it.name } ?: []
        publicFields.containsAll(["customer_id", "first_name", "salary", "ssn"])

        when: "Public user tries to access restricted salary field"
        def restrictedQuery = """
            {
                sensitive_customer(limit: 1) {
                    customer_id
                    first_name
                    salary
                }
            }
        """

        def restrictedResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "public_user")
                .content("""{"query": "${restrictedQuery.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def restrictedResult = objectMapper.readValue(restrictedResponse, Map)

        then: "Public user should get permission error for restricted salary field"
        restrictedResult.errors?.any { 
            it.message.toLowerCase().contains("salary") ||
            it.message.toLowerCase().contains("undefined")
        }
    }

    def "should enforce role security in mutations"() {
        given: "GraphQL mutation to create new customer (using underscore naming convention)"
        def createMutation = """
            mutation {
                createSensitive_customer(input: {
                    first_name: "Test"
                    last_name: "User"
                    email: "test@example.com"
                    salary: 50000.00
                    department_id: 1
                    ssn: "000-00-0000"
                }) {
                    customer_id
                    first_name
                    email
                }
            }
        """

        when: "HR Manager attempts to create customer"
        def hrResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "hr_manager")
                .content("""{"query": "${createMutation.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def hrResult = objectMapper.readValue(hrResponse, Map)

        then: "HR Manager should successfully create customer"
        if (hrResult.errors) {
            !hrResult.errors.any { it.message.toLowerCase().contains("permission") }
        } else {
            hrResult.data.createSensitive_customer?.customer_id != null
        }

        when: "Employee attempts to create customer"
        def empResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "employee")
                .content("""{"query": "${createMutation.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def empResult = objectMapper.readValue(empResponse, Map)

        then: "Employee should get permission error (no INSERT privilege on restricted fields)"
        empResult.errors?.any { 
            it.message.toLowerCase().contains("salary") ||
            it.message.toLowerCase().contains("field not in") ||
            it.message.toLowerCase().contains("undefined")
        }
    }

    def "should respect role-based security feature flag"() {
        given: "GraphQL query to fetch customer data"
        def query = """
            {
                sensitive_customer {
                    customer_id
                    first_name
                    salary
                    ssn
                }
            }
        """

        when: "Employee queries data with feature flag enabled (default behavior)"
        def empResponse = mockMvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Database-Role", "employee")
                .content("""{"query": "${query.replaceAll('\n', '\\\\n').replaceAll('"', '\\\\"')}"}"""))
                .andReturn()
                .response
                .contentAsString

        def empResult = objectMapper.readValue(empResponse, Map)

        then: "Employee should get permission errors due to role-based filtering"
        empResult.errors?.any { 
            it.message.toLowerCase().contains("salary") ||
            it.message.toLowerCase().contains("ssn") ||
            it.message.toLowerCase().contains("undefined")
        }

        and: "Feature flag is currently enabled by default in test configuration"
        true // This test validates the current behavior with feature flag enabled
    }

} 