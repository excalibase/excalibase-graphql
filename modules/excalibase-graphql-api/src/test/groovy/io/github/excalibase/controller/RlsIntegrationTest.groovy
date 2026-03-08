package io.github.excalibase.controller

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * Integration test for User Context-Based RLS (Row Level Security).
 * Validates that PostgreSQL RLS policies work correctly with session variables.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(locations = "classpath:application-test.yaml")
class RlsIntegrationTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")

    @Autowired
    JdbcTemplate jdbcTemplate

    private JdbcTemplate appUserJdbcTemplate
    private TransactionTemplate transactionTemplate

    @org.springframework.test.context.DynamicPropertySource
    static void configureProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("app.allowed-schema", { "public" })
        registry.add("app.database-type", { "postgres" })
    }

    def setupSpec() {
        postgres.start()
        setupTestData()
    }

    def setup() {
        // Create non-superuser connection (superusers bypass RLS!)
        def dataSource = new DriverManagerDataSource()
        dataSource.setDriverClassName("org.postgresql.Driver")
        dataSource.setUrl(postgres.getJdbcUrl())
        dataSource.setUsername("app_user")
        dataSource.setPassword("apppass")
        appUserJdbcTemplate = new JdbcTemplate(dataSource)

        def transactionManager = new DataSourceTransactionManager(dataSource)
        transactionTemplate = new TransactionTemplate(transactionManager)
    }

    def cleanupSpec() {
        postgres.stop()
    }

    private static void setupTestData() {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {

            // Create test table
            statement.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id SERIAL PRIMARY KEY,
                    user_id VARCHAR(50) NOT NULL,
                    product VARCHAR(100) NOT NULL,
                    amount DECIMAL(10,2) NOT NULL
                )
            """)

            // Insert test data
            statement.execute("""
                INSERT INTO orders (user_id, product, amount) VALUES
                ('alice', 'Laptop', 1200.00),
                ('alice', 'Mouse', 25.00),
                ('bob', 'Keyboard', 80.00),
                ('bob', 'Monitor', 350.00)
            """)

            // Create non-superuser role
            statement.execute("DROP ROLE IF EXISTS app_user")
            statement.execute("CREATE ROLE app_user LOGIN PASSWORD 'apppass'")
            statement.execute("GRANT CONNECT ON DATABASE testdb TO app_user")
            statement.execute("GRANT USAGE ON SCHEMA public TO app_user")
            statement.execute("GRANT SELECT, INSERT ON orders TO app_user")
            statement.execute("GRANT USAGE, SELECT ON SEQUENCE orders_id_seq TO app_user")

            // Enable RLS
            statement.execute("ALTER TABLE orders ENABLE ROW LEVEL SECURITY")

            // Create RLS policy using current_setting
            statement.execute("""
                CREATE POLICY user_orders_policy ON orders
                FOR ALL
                USING (user_id = current_setting('request.user_id', true))
            """)
        }
    }

    def "should filter rows based on user context session variable"() {
        when: "querying with user_id set to alice"
        def aliceOrders = transactionTemplate.execute { status ->
            appUserJdbcTemplate.execute("SET LOCAL request.user_id = 'alice'")
            return appUserJdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id")
        }

        then: "only alice's orders are returned"
        aliceOrders.size() == 2
        aliceOrders.every { it.user_id == 'alice' }

        when: "querying with user_id set to bob"
        def bobOrders = transactionTemplate.execute { status ->
            appUserJdbcTemplate.execute("SET LOCAL request.user_id = 'bob'")
            return appUserJdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id")
        }

        then: "only bob's orders are returned"
        bobOrders.size() == 2
        bobOrders.every { it.user_id == 'bob' }
    }

    def "should return empty when no user context is set"() {
        when: "querying without setting user context"
        def orders = transactionTemplate.execute { status ->
            return appUserJdbcTemplate.queryForList("SELECT * FROM orders")
        }

        then: "no orders are returned (RLS blocks)"
        orders.size() == 0
    }

    def "should allow INSERT with matching user context"() {
        when: "inserting with user context set"
        transactionTemplate.execute { status ->
            appUserJdbcTemplate.execute("SET LOCAL request.user_id = 'alice'")
            appUserJdbcTemplate.update(
                "INSERT INTO orders (user_id, product, amount) VALUES (?, ?, ?)",
                'alice', 'Tablet', 450.00
            )
            return null
        }

        and: "querying as alice"
        def orders = transactionTemplate.execute { status ->
            appUserJdbcTemplate.execute("SET LOCAL request.user_id = 'alice'")
            return appUserJdbcTemplate.queryForList(
                "SELECT * FROM orders WHERE product = 'Tablet'"
            )
        }

        then: "order is visible"
        orders.size() == 1
        orders[0].user_id == 'alice'
        orders[0].product == 'Tablet'
    }
}
