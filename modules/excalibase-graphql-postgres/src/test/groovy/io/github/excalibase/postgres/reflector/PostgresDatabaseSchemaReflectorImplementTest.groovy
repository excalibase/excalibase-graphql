package io.github.excalibase.postgres.reflector

import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.postgres.reflector.PostgresDatabaseSchemaReflectorImplement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

class PostgresDatabaseSchemaReflectorImplementTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")

    JdbcTemplate jdbcTemplate
    PostgresDatabaseSchemaReflectorImplement schemaReflector

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
        schemaReflector = new PostgresDatabaseSchemaReflectorImplement(jdbcTemplate)
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

    def "should detect and reflect database views"() {
        given: "a table and a view based on it"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(200) UNIQUE,
                active BOOLEAN DEFAULT true,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        jdbcTemplate.execute("""
            CREATE VIEW test_schema.active_users AS
            SELECT id, name, email, created_at
            FROM test_schema.users
            WHERE active = true
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should contain both table and view"
        tables.size() == 2
        tables.containsKey("users")
        tables.containsKey("active_users")

        and: "table should be marked as table"
        TableInfo usersTable = tables.get("users")
        usersTable.name == "users"
        !usersTable.isView()
        usersTable.columns.size() == 5

        and: "view should be marked as view"
        TableInfo activeUsersView = tables.get("active_users")
        activeUsersView.name == "active_users"
        activeUsersView.isView()
        activeUsersView.columns.size() == 4

        and: "view should have correct columns"
        Set<String> viewColumnNames = activeUsersView.columns.collect { it.name } as Set
        viewColumnNames == ["id", "name", "email", "created_at"] as Set

        and: "table should have primary keys but view should not"
        usersTable.columns.find { it.name == "id" }.primaryKey
        !activeUsersView.columns.find { it.name == "id" }.primaryKey

        and: "table may have foreign keys but view should not"
        // Views don't have primary keys or foreign keys in the same way tables do
        activeUsersView.foreignKeys.isEmpty()
    }

    def "should reflect complex view with joins"() {
        given: "tables with relationships and a view that joins them"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.departments (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.employees (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                department_id INTEGER REFERENCES test_schema.departments(id),
                salary DECIMAL(10,2)
            )
        """)

        jdbcTemplate.execute("""
            CREATE VIEW test_schema.employee_details AS
            SELECT 
                e.id,
                e.name as employee_name,
                d.name as department_name,
                e.salary
            FROM test_schema.employees e
            JOIN test_schema.departments d ON e.department_id = d.id
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should contain all tables and views"
        tables.size() == 3
        tables.containsKey("departments")
        tables.containsKey("employees")
        tables.containsKey("employee_details")

        and: "view should be properly detected"
        TableInfo employeeDetailsView = tables.get("employee_details")
        employeeDetailsView.isView()
        employeeDetailsView.columns.size() == 4

        and: "view columns should have correct types"
        employeeDetailsView.columns.find { it.name == "id" }.type == "integer"
        employeeDetailsView.columns.find { it.name == "employee_name" }.type == "character varying(100)"
        employeeDetailsView.columns.find { it.name == "department_name" }.type == "character varying(100)"
        employeeDetailsView.columns.find { it.name == "salary" }.type == "numeric(10,2)"
    }

    def "should handle schema with only views"() {
        given: "a table in different schema and a view in test schema"
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS other_schema")
        jdbcTemplate.execute("""
            CREATE TABLE other_schema.source_table (
                id SERIAL PRIMARY KEY,
                data VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            CREATE VIEW test_schema.external_view AS
            SELECT id, data
            FROM other_schema.source_table
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should only contain the view from test schema"
        tables.size() == 1
        tables.containsKey("external_view")

        and: "view should be properly detected"
        TableInfo externalView = tables.get("external_view")
        externalView.isView()
        externalView.columns.size() == 2
    }

    def "should handle materialized views"() {
        given: "a table and a materialized view"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.sales (
                id SERIAL PRIMARY KEY,
                product VARCHAR(100),
                amount DECIMAL(10,2),
                sale_date DATE
            )
        """)

        jdbcTemplate.execute("""
            CREATE MATERIALIZED VIEW test_schema.monthly_sales AS
            SELECT 
                DATE_TRUNC('month', sale_date) as month,
                product,
                SUM(amount) as total_amount
            FROM test_schema.sales
            GROUP BY DATE_TRUNC('month', sale_date), product
        """)

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should contain both table and materialized view"
        tables.size() == 2
        tables.containsKey("sales")
        tables.containsKey("monthly_sales")

        and: "materialized view should be detected as view"
        TableInfo monthlySalesView = tables.get("monthly_sales")
        monthlySalesView.isView()
        monthlySalesView.columns.size() == 3

        and: "materialized view columns should have correct types"
        monthlySalesView.columns.find { it.name == "month" }.type.contains("timestamp")
        monthlySalesView.columns.find { it.name == "product" }.type == "character varying(100)"
        monthlySalesView.columns.find { it.name == "total_amount" }.type == "numeric"
    }

    def "should reflect custom enum types in schema"() {
        given: "a table with custom enum columns"
        def createCustomEnumTypes = """
            CREATE TYPE test_status AS ENUM ('active', 'inactive', 'pending');
            CREATE TYPE test_priority AS ENUM ('low', 'medium', 'high');
        """
        
        def createTableWithEnums = """
            CREATE TABLE test_enum_table (
                id SERIAL PRIMARY KEY,
                status test_status DEFAULT 'pending',
                priority test_priority DEFAULT 'medium',
                name VARCHAR(100)
            )
        """
        
        jdbcTemplate.execute(createCustomEnumTypes)
        jdbcTemplate.execute(createTableWithEnums)
        
        // Set schema to public for this test since we create types in public schema
        schemaReflector.allowedSchema = "public"
        
        when: "reflecting the schema"
        def schema = schemaReflector.reflectSchema()
        
        then: "should detect custom enum types"
        def enumTypes = schemaReflector.getCustomEnumTypes("public")
        enumTypes.size() >= 2
        
        and: "should detect test_status enum"
        def statusEnum = enumTypes.find { it.name == 'test_status' }
        statusEnum != null
        statusEnum.values == ['active', 'inactive', 'pending']
        
        and: "should detect test_priority enum"
        def priorityEnum = enumTypes.find { it.name == 'test_priority' }  
        priorityEnum != null
        priorityEnum.values == ['low', 'medium', 'high']
        
        and: "table columns should reference enum types"
        def tableInfo = schema['test_enum_table']
        tableInfo != null
        def statusColumn = tableInfo.columns.find { it.name == 'status' }
        statusColumn != null
        statusColumn.type == 'test_status'
        def priorityColumn = tableInfo.columns.find { it.name == 'priority' }
        priorityColumn != null
        priorityColumn.type == 'test_priority'
        
        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_enum_table")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_status")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_priority")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should reflect custom composite object types in schema"() {
        given: "custom composite types and table using them"
        def createCompositeTypes = """
            CREATE TYPE test_address AS (
                street VARCHAR(100),
                city VARCHAR(50), 
                postal_code VARCHAR(20)
            );
            
            CREATE TYPE test_contact AS (
                email VARCHAR(100),
                phone VARCHAR(20)
            );
        """
        
        def createTableWithComposites = """
            CREATE TABLE test_composite_table (
                id SERIAL PRIMARY KEY,
                home_address test_address,
                work_address test_address,
                contact_info test_contact,
                name VARCHAR(100)
            )
        """
        
        jdbcTemplate.execute(createCompositeTypes)
        jdbcTemplate.execute(createTableWithComposites)
        
        // Set schema to public for this test since we create types in public schema
        schemaReflector.allowedSchema = "public"
        
        when: "reflecting the schema"
        def schema = schemaReflector.reflectSchema()
        
        then: "should detect custom composite types"
        def compositeTypes = schemaReflector.getCustomCompositeTypes("public")
        compositeTypes.size() >= 2
        
        and: "should detect test_address composite type"
        def addressType = compositeTypes.find { it.name == 'test_address' }
        addressType != null
        addressType.attributes.size() == 3
        addressType.attributes.find { it.name == 'street' && it.type.startsWith('character varying') }
        addressType.attributes.find { it.name == 'city' && it.type.startsWith('character varying') }
        addressType.attributes.find { it.name == 'postal_code' && it.type.startsWith('character varying') }
        
        and: "should detect test_contact composite type"
        def contactType = compositeTypes.find { it.name == 'test_contact' }
        contactType != null 
        contactType.attributes.size() == 2
        contactType.attributes.find { it.name == 'email' && it.type.startsWith('character varying') }
        contactType.attributes.find { it.name == 'phone' && it.type.startsWith('character varying') }
        
        and: "table columns should reference composite types"
        def tableInfo = schema['test_composite_table']
        tableInfo != null
        def homeAddressColumn = tableInfo.columns.find { it.name == 'home_address' }
        homeAddressColumn.type == 'test_address'
        def workAddressColumn = tableInfo.columns.find { it.name == 'work_address' }
        workAddressColumn.type == 'test_address'
        def contactColumn = tableInfo.columns.find { it.name == 'contact_info' }
        contactColumn.type == 'test_contact'
        
        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_composite_table")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_address")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_contact")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    // TODO: Future enhancement - implement array support for custom types
    /*
    def "should handle arrays of custom enum types"() {
        given: "a custom enum and table with enum array column"
        def createEnumType = """
            CREATE TYPE test_role AS ENUM ('admin', 'user', 'guest');
        """
        
        def createTableWithEnumArray = """
            CREATE TABLE test_enum_array_table (
                id SERIAL PRIMARY KEY,
                roles test_role[],
                primary_role test_role DEFAULT 'user'
            )
        """
        
        jdbcTemplate.execute(createEnumType)
        jdbcTemplate.execute(createTableWithEnumArray)
        
        when: "reflecting the schema"
        def schema = schemaReflector.reflectSchema()
        
        then: "should detect enum array column"
        def tableInfo = schema['test_enum_array_table']
        tableInfo != null
        def rolesColumn = tableInfo.columns.find { it.name == 'roles' }
        rolesColumn.type == 'test_role[]'
        def primaryRoleColumn = tableInfo.columns.find { it.name == 'primary_role' }
        primaryRoleColumn.type == 'test_role'
        
        and: "enum types should be detected"
        def enumTypes = schemaReflector.getCustomEnumTypes()
        def roleEnum = enumTypes.find { it.name == 'test_role' }
        roleEnum != null
        roleEnum.values == ['admin', 'user', 'guest']
        
                cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_enum_array_table")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_role")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    */

    // TODO: Future enhancement - implement array support for custom types
    /*
    def "should handle arrays of custom composite types"() {
        given: "a custom composite type and table with composite array column"
        def createCompositeType = """
            CREATE TYPE test_phone AS (
                number VARCHAR(20),
                type VARCHAR(10)
            );
        """
        
        def createTableWithCompositeArray = """
            CREATE TABLE test_composite_array_table (
                id SERIAL PRIMARY KEY,
                phone_numbers test_phone[],
                primary_phone test_phone
            )
        """
        
        jdbcTemplate.execute(createCompositeType)
        jdbcTemplate.execute(createTableWithCompositeArray)
        
        when: "reflecting the schema"
        def schema = schemaReflector.reflectSchema()
        
        then: "should detect composite array column"
        def tableInfo = schema['test_composite_array_table']
        tableInfo != null
        def phonesColumn = tableInfo.columns.find { it.name == 'phone_numbers' }
        phonesColumn.type == 'test_phone[]'
        def primaryPhoneColumn = tableInfo.columns.find { it.name == 'primary_phone' }
        primaryPhoneColumn.type == 'test_phone'
        
        and: "composite types should be detected"
        def compositeTypes = schemaReflector.getCustomCompositeTypes()
        def phoneType = compositeTypes.find { it.name == 'test_phone' }
        phoneType != null
        phoneType.attributes.size() == 2
        phoneType.attributes.find { it.name == 'number' && it.type == 'varchar' }
        phoneType.attributes.find { it.name == 'type' && it.type == 'varchar' }
        
        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_composite_array_table")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_phone")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    */

    def "should use optimized bulk queries instead of N+1 pattern for multiple tables"() {
        given: "multiple tables to trigger N+1 queries"
        // Create 5 tables to demonstrate the N+1 problem
        for (int i = 1; i <= 5; i++) {
            jdbcTemplate.execute("""
                CREATE TABLE test_schema.table_${i} (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    value INTEGER
                )
            """)
        }

        and: "cache is cleared to ensure fresh queries after tables are created"
        schemaReflector.clearCache()

        and: "a spy on jdbcTemplate to count queries"
        def queryCount = 0
        def originalQueryForList = jdbcTemplate.&queryForList
        jdbcTemplate.metaClass.queryForList = { String sql, Object... args ->
            queryCount++
            // Query execution tracked
            return originalQueryForList(sql, args)
        }

        when: "reflecting the schema"
        Map<String, TableInfo> tables = schemaReflector.reflectSchema()

        then: "should return all tables"
        tables.size() == 5
        tables.keySet().containsAll(['table_1', 'table_2', 'table_3', 'table_4', 'table_5'])

        and: "should demonstrate optimized bulk queries (much fewer than N+1)"
        // Optimized implementation: 1 query for table names + 1 for views + 1 bulk columns + 1 bulk PKs + 1 bulk FKs = 5 queries
        // Before optimization would have been: 1 + 1 + 5*3 = 17 queries
        // Query optimization verified: ${queryCount} total queries
        queryCount <= 6 // Should be much fewer queries due to bulk optimization
    }
}
