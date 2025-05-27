package io.github.excalibase.schema.reflector.postgres

import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

class PostgreSqlDatabaseSchemaReflectorImplementTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")

    JdbcTemplate jdbcTemplate
    PostgreSqlDatabaseSchemaReflectorImplement schemaReflector

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
        schemaReflector = new PostgreSqlDatabaseSchemaReflectorImplement(jdbcTemplate)
        // Set the allowed schema using reflection to simulate @Value injection
        schemaReflector.allowedSchema = "test_schema"
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS test_schema")
    }

    def cleanup() {
        // Clean up test data after each test
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS test_schema CASCADE")
    }

    def "should reflect simple table with basic columns"() {
        given: "a simple table with various column types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.simple_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_active BOOLEAN DEFAULT true,
                price DECIMAL(10,2)
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should contain the simple table with correct information"
        tables.size() == 1
        tables.containsKey("simple_table")

        TableInfo tableInfo = tables.get("simple_table")
        tableInfo.name == "simple_table"
        tableInfo.columns.size() == 6

        ColumnInfo idColumn = tableInfo.columns.find { it.name == "id" }
        idColumn.name == "id"
        idColumn.type == "integer"
        idColumn.primaryKey
        !idColumn.nullable

        ColumnInfo nameColumn = tableInfo.columns.find { it.name == "name" }
        nameColumn.name == "name"
        nameColumn.type == "character varying(100)"
        !nameColumn.primaryKey
        !nameColumn.nullable

        ColumnInfo descriptionColumn = tableInfo.columns.find { it.name == "description" }
        descriptionColumn.name == "description"
        descriptionColumn.type == "text"
        !descriptionColumn.primaryKey
        descriptionColumn.nullable
    }

    def "should reflect tables with foreign key relationships"() {
        given: "tables with foreign key relationships"
        // Create parent tables
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.customers (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(255) UNIQUE
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                price DECIMAL(10,2)
            )
        """)

        // Create child table with foreign keys
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.orders (
                id SERIAL PRIMARY KEY,
                customer_id INTEGER NOT NULL,
                order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                total_amount DECIMAL(10,2),
                FOREIGN KEY (customer_id) REFERENCES test_schema.customers(id)
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.order_items (
                id SERIAL PRIMARY KEY,
                order_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                unit_price DECIMAL(10,2),
                FOREIGN KEY (order_id) REFERENCES test_schema.orders(id),
                FOREIGN KEY (product_id) REFERENCES test_schema.products(id)
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should contain all tables with correct foreign key information"
        tables.size() == 4
        tables.containsKey("customers")
        tables.containsKey("products")
        tables.containsKey("orders")
        tables.containsKey("order_items")

        // Check customers table (no foreign keys)
        TableInfo customersTable = tables.get("customers")
        customersTable.foreignKeys.size() == 0

        // Check orders table (one foreign key)
        TableInfo ordersTable = tables.get("orders")
        ordersTable.foreignKeys.size() == 1
        ForeignKeyInfo ordersFk = ordersTable.foreignKeys[0]
        ordersFk.columnName == "customer_id"
        ordersFk.referencedTable == "customers"
        ordersFk.referencedColumn == "id"

        // Check order_items table (two foreign keys)
        TableInfo orderItemsTable = tables.get("order_items")
        orderItemsTable.foreignKeys.size() == 2

        ForeignKeyInfo orderFk = orderItemsTable.foreignKeys.find { it.columnName == "order_id" }
        orderFk.columnName == "order_id"
        orderFk.referencedTable == "orders"
        orderFk.referencedColumn == "id"

        ForeignKeyInfo productFk = orderItemsTable.foreignKeys.find { it.columnName == "product_id" }
        productFk.columnName == "product_id"
        productFk.referencedTable == "products"
        productFk.referencedColumn == "id"
    }

    def "should reflect table with composite primary key"() {
        given: "a table with composite primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.customers (
                id SERIAL,
                name VARCHAR(100),
                PRIMARY KEY (id)
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.products (
                id SERIAL,
                name VARCHAR(100),
                PRIMARY KEY (id)
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.order_items (
                customer_id INTEGER,
                product_id INTEGER,
                quantity INTEGER NOT NULL,
                price DECIMAL(10,2),
                PRIMARY KEY (customer_id, product_id),
                FOREIGN KEY (customer_id) REFERENCES test_schema.customers(id),
                FOREIGN KEY (product_id) REFERENCES test_schema.products(id)
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should correctly identify composite primary key"
        tables.containsKey("order_items")

        TableInfo orderItemsTable = tables.get("order_items")
        List<ColumnInfo> primaryKeyColumns = orderItemsTable.columns.findAll { it.primaryKey }
        primaryKeyColumns.size() == 2

        List<String> primaryKeyNames = primaryKeyColumns.collect { it.name }.sort()
        primaryKeyNames == ["customer_id", "product_id"]
    }

    def "should handle empty schema"() {
        given: "an empty schema"

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should return empty map"
        tables.size() == 0
        tables.isEmpty()
    }

    def "should handle table with no primary key"() {
        given: "a table without primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.simple_table (
                name VARCHAR(100),
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should reflect table correctly with no primary keys"
        tables.size() == 1
        tables.containsKey("simple_table")

        TableInfo tableInfo = tables.get("simple_table")
        tableInfo.columns.size() == 3
        tableInfo.columns.every { !it.primaryKey }
        tableInfo.foreignKeys.size() == 0
    }

    def "should handle various PostgreSQL data types"() {
        given: "a table with various PostgreSQL data types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.data_types_table (
                id SERIAL PRIMARY KEY,
                text_col TEXT,
                varchar_col VARCHAR(50),
                char_col CHAR(10),
                int_col INTEGER,
                bigint_col BIGINT,
                decimal_col DECIMAL(10,2),
                numeric_col NUMERIC(15,5),
                real_col REAL,
                double_col DOUBLE PRECISION,
                boolean_col BOOLEAN,
                date_col DATE,
                timestamp_col TIMESTAMP,
                timestamptz_col TIMESTAMPTZ,
                uuid_col UUID,
                json_col JSON,
                jsonb_col JSONB,
                array_col INTEGER[]
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should correctly identify all data types"
        tables.containsKey("data_types_table")

        TableInfo tableInfo = tables.get("data_types_table")
        tableInfo.columns.size() == 18

        tableInfo.columns.find { it.name == "text_col" }.type == "text"
        tableInfo.columns.find { it.name == "varchar_col" }.type == "character varying(50)"
        tableInfo.columns.find { it.name == "int_col" }.type == "integer"
        tableInfo.columns.find { it.name == "bigint_col" }.type == "bigint"
        tableInfo.columns.find { it.name == "boolean_col" }.type == "boolean"
        tableInfo.columns.find { it.name == "uuid_col" }.type == "uuid"
        tableInfo.columns.find { it.name == "array_col" }.type == "integer[]"
    }

    def "should only reflect tables from allowed schema"() {
        given: "tables in different schemas"
        // Create table in allowed schema
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.allowed_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        // Create table in different schema
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS other_schema")
        jdbcTemplate.execute("""
            CREATE TABLE other_schema.other_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should only contain tables from allowed schema"
        tables.size() == 1
        tables.containsKey("allowed_table")
        !tables.containsKey("other_table")
    }

    def "should handle schema configuration changes"() {
        given: "multiple schemas with tables"
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS schema_a")
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS schema_b")

        jdbcTemplate.execute("""
            CREATE TABLE schema_a.table_a (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE schema_b.table_b (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        when: "reflecting schema A"
        schemaReflector.allowedSchema = "schema_a"
        Map<String, TableInfo> tablesA = schemaReflector.reflectSchema()

        then: "should only contain tables from schema A"
        tablesA.size() == 1
        tablesA.containsKey("table_a")
        !tablesA.containsKey("table_b")

        when: "changing to schema B"
        schemaReflector.allowedSchema = "schema_b"
        Map<String, TableInfo> tablesB = schemaReflector.reflectSchema()

        then: "should only contain tables from schema B"
        tablesB.size() == 1
        tablesB.containsKey("table_b")
        !tablesB.containsKey("table_a")
    }
}
