package io.github.excalibase.postgres.fetcher

import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingFieldSelectionSet
import graphql.schema.SelectedField

import io.github.excalibase.config.AppConfig
import io.github.excalibase.constant.DatabaseType
import io.github.excalibase.constant.SupportedDatabaseConstant
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector
import io.github.excalibase.service.ServiceLookup
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

/**
 * Test suite for aggregate data fetcher functionality.
 */
class PostgresAggregateDataFetcherTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")

    JdbcTemplate jdbcTemplate
    NamedParameterJdbcTemplate namedParameterJdbcTemplate
    PostgresDatabaseDataFetcherImplement dataFetcher
    ServiceLookup serviceLookup
    AppConfig appConfig
    IDatabaseSchemaReflector schemaReflector

    def setupSpec() {
        postgres.start()
    }

    def setup() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        )
        jdbcTemplate = new JdbcTemplate(dataSource)
        namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(dataSource)

        // Mock dependencies
        serviceLookup = Mock(ServiceLookup)
        appConfig = new AppConfig()
        appConfig.setAllowedSchema("test_schema")
        appConfig.setDatabaseType(DatabaseType.POSTGRES)
        schemaReflector = Mock(IDatabaseSchemaReflector)

        // Configure mocks
        serviceLookup.forBean(IDatabaseSchemaReflector.class, SupportedDatabaseConstant.POSTGRES) >> schemaReflector

        dataFetcher = new PostgresDatabaseDataFetcherImplement(
                jdbcTemplate,
                namedParameterJdbcTemplate,
                serviceLookup,
                appConfig
        )

        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS test_schema")
    }

    def cleanup() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS test_schema CASCADE")
    }

    def "should count all records without filters"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.orders (
                id SERIAL PRIMARY KEY,
                customer_id INTEGER NOT NULL,
                total_amount DECIMAL(10,2) NOT NULL,
                status VARCHAR(50) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.orders (customer_id, total_amount, status) VALUES
            (1, 100.50, 'completed'),
            (1, 250.00, 'completed'),
            (2, 75.25, 'pending'),
            (3, 500.00, 'completed'),
            (3, 150.00, 'cancelled')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "orders",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "total_amount", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "status", type: "character varying(50)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["orders": tableInfo]

        and: "mocked DataFetchingEnvironment for count aggregate"
        def environment = createMockAggregateEnvironment(["count"], [:])

        when: "executing aggregate data fetcher"
        def fetcher = dataFetcher.createAggregateDataFetcher("orders")
        def result = fetcher.get(environment)

        then: "should return count of all orders"
        result.count == 5
    }

    def "should count records with filters"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                price DECIMAL(10,2) NOT NULL,
                in_stock BOOLEAN DEFAULT true
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.products (name, price, in_stock) VALUES
            ('Laptop', 999.99, true),
            ('Mouse', 29.99, true),
            ('Keyboard', 79.99, false),
            ('Monitor', 299.99, true),
            ('Headphones', 149.99, false)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "in_stock", type: "boolean", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["products": tableInfo]

        and: "mocked DataFetchingEnvironment with filters"
        def environment = createMockAggregateEnvironment(["count"], ["in_stock": true])

        when: "executing aggregate data fetcher with filter"
        def fetcher = dataFetcher.createAggregateDataFetcher("products")
        def result = fetcher.get(environment)

        then: "should return count of only in-stock products"
        result.count == 3
    }

    def "should compute sum aggregate on numeric columns"() {
        given: "a table with numeric data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.sales (
                id SERIAL PRIMARY KEY,
                product_name VARCHAR(100) NOT NULL,
                quantity INTEGER NOT NULL,
                unit_price DECIMAL(10,2) NOT NULL,
                total DECIMAL(10,2) NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.sales (product_name, quantity, unit_price, total) VALUES
            ('Product A', 10, 25.50, 255.00),
            ('Product B', 5, 50.00, 250.00),
            ('Product C', 15, 10.00, 150.00)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "sales",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "product_name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "quantity", type: "integer", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "unit_price", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "total", type: "numeric", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["sales": tableInfo]

        and: "mocked DataFetchingEnvironment for sum aggregate"
        def environment = createMockAggregateEnvironment(
                ["sum"],
                [:],
                ["sum": ["total", "quantity"]]
        )

        when: "executing sum aggregate"
        def fetcher = dataFetcher.createAggregateDataFetcher("sales")
        def result = fetcher.get(environment)

        then: "should return sum of specified columns"
        result.sum.total == 655.00
        result.sum.quantity == 30
    }

    def "should compute avg aggregate on numeric columns"() {
        given: "a table with numeric data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.student_scores (
                id SERIAL PRIMARY KEY,
                student_name VARCHAR(100) NOT NULL,
                math_score INTEGER NOT NULL,
                english_score INTEGER NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.student_scores (student_name, math_score, english_score) VALUES
            ('Alice', 85, 90),
            ('Bob', 78, 82),
            ('Charlie', 92, 88),
            ('David', 88, 94)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "student_scores",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "student_name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "math_score", type: "integer", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "english_score", type: "integer", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["student_scores": tableInfo]

        and: "mocked DataFetchingEnvironment for avg aggregate"
        def environment = createMockAggregateEnvironment(
                ["avg"],
                [:],
                ["avg": ["math_score", "english_score"]]
        )

        when: "executing avg aggregate"
        def fetcher = dataFetcher.createAggregateDataFetcher("student_scores")
        def result = fetcher.get(environment)

        then: "should return average of specified columns"
        result.avg.math_score == 85.75
        result.avg.english_score == 88.5
    }

    def "should compute min and max aggregates"() {
        given: "a table with comparable data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.temperature_readings (
                id SERIAL PRIMARY KEY,
                location VARCHAR(100) NOT NULL,
                temperature DECIMAL(5,2) NOT NULL,
                recorded_at TIMESTAMP NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.temperature_readings (location, temperature, recorded_at) VALUES
            ('Room A', 22.5, '2024-01-01 10:00:00'),
            ('Room A', 25.0, '2024-01-01 14:00:00'),
            ('Room A', 20.0, '2024-01-01 18:00:00'),
            ('Room B', 18.5, '2024-01-01 10:00:00')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "temperature_readings",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "location", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "temperature", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "recorded_at", type: "timestamp", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["temperature_readings": tableInfo]

        and: "mocked DataFetchingEnvironment for min/max aggregates"
        def environment = createMockAggregateEnvironment(
                ["min", "max"],
                [:],
                [
                        "min": ["temperature"],
                        "max": ["temperature"]
                ]
        )

        when: "executing min/max aggregates"
        def fetcher = dataFetcher.createAggregateDataFetcher("temperature_readings")
        def result = fetcher.get(environment)

        then: "should return min and max values"
        result.min.temperature == 18.5
        result.max.temperature == 25.0
    }

    def "should compute multiple aggregates together"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.transactions (
                id SERIAL PRIMARY KEY,
                user_id INTEGER NOT NULL,
                amount DECIMAL(10,2) NOT NULL,
                fee DECIMAL(10,2) NOT NULL,
                status VARCHAR(50) NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.transactions (user_id, amount, fee, status) VALUES
            (1, 100.00, 2.50, 'completed'),
            (1, 250.00, 5.00, 'completed'),
            (2, 75.00, 1.50, 'completed'),
            (3, 500.00, 10.00, 'completed'),
            (3, 150.00, 3.00, 'pending')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "transactions",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "user_id", type: "integer", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "amount", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "fee", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "status", type: "character varying(50)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["transactions": tableInfo]

        and: "mocked DataFetchingEnvironment for multiple aggregates"
        def environment = createMockAggregateEnvironment(
                ["count", "sum", "avg", "min", "max"],
                ["status": "completed"],
                [
                        "sum": ["amount", "fee"],
                        "avg": ["amount"],
                        "min": ["amount"],
                        "max": ["amount"]
                ]
        )

        when: "executing multiple aggregates with filter"
        def fetcher = dataFetcher.createAggregateDataFetcher("transactions")
        def result = fetcher.get(environment)

        then: "should return all requested aggregates"
        result.count == 4
        result.sum.amount == 925.00
        result.sum.fee == 19.00
        result.avg.amount == 231.25
        result.min.amount == 75.00
        result.max.amount == 500.00
    }

    def "should handle empty result set"() {
        given: "an empty table"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.empty_orders (
                id SERIAL PRIMARY KEY,
                amount DECIMAL(10,2) NOT NULL
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "empty_orders",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "amount", type: "numeric", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["empty_orders": tableInfo]

        and: "mocked DataFetchingEnvironment for count"
        def environment = createMockAggregateEnvironment(["count"], [:])

        when: "executing aggregate on empty table"
        def fetcher = dataFetcher.createAggregateDataFetcher("empty_orders")
        def result = fetcher.get(environment)

        then: "should return count as 0"
        result.count == 0
    }

    // Helper methods

    private DataFetchingEnvironment createMockAggregateEnvironment(
            List<String> aggregateFunctions,
            Map<String, Object> arguments,
            Map<String, List<String>> columnSelections = [:]) {

        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)

        def fields = aggregateFunctions.collect { funcName ->
            def field = Mock(SelectedField)
            field.getName() >> funcName

            // For aggregate functions that require column selection (sum, avg, min, max)
            if (columnSelections.containsKey(funcName)) {
                def nestedSelectionSet = Mock(DataFetchingFieldSelectionSet)
                def columnFields = columnSelections[funcName].collect { columnName ->
                    def columnField = Mock(SelectedField)
                    columnField.getName() >> columnName
                    columnField
                }
                nestedSelectionSet.getFields() >> columnFields
                field.getSelectionSet() >> nestedSelectionSet
            } else {
                field.getSelectionSet() >> null
            }

            field
        }

        selectionSet.getFields() >> fields
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> arguments
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }
}
