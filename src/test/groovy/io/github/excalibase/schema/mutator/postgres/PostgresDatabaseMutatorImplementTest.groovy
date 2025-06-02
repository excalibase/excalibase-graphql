package io.github.excalibase.schema.mutator.postgres

import graphql.schema.DataFetchingEnvironment
import io.github.excalibase.config.AppConfig
import io.github.excalibase.constant.DatabaseType
import io.github.excalibase.constant.SupportedDatabaseConstant
import io.github.excalibase.exception.DataMutationException
import io.github.excalibase.exception.NotFoundException
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector
import io.github.excalibase.service.ServiceLookup
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import spock.lang.Shared
import spock.lang.Specification

import javax.sql.DataSource

class PostgresDatabaseMutatorImplementTest extends Specification {

    @Shared
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass")

    JdbcTemplate jdbcTemplate
    NamedParameterJdbcTemplate namedParameterJdbcTemplate
    PostgresDatabaseMutatorImplement mutator
    ServiceLookup serviceLookup
    AppConfig appConfig
    IDatabaseSchemaReflector schemaReflector
    TransactionTemplate transactionTemplate

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
        transactionTemplate = Mock(TransactionTemplate)

        // Configure mocks
        serviceLookup.forBean(IDatabaseSchemaReflector.class, SupportedDatabaseConstant.POSTGRES) >> schemaReflector

        mutator = new PostgresDatabaseMutatorImplement(
                jdbcTemplate,
                namedParameterJdbcTemplate,
                serviceLookup,
                appConfig,
                transactionTemplate
        )

        // Set up test schema
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS test_schema")
    }

    def cleanup() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS test_schema CASCADE")
    }

    def "should create new record successfully"() {
        given: "a table for testing create operations"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(255),
                age INTEGER,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "users",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "character varying(255)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "age", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["users": tableInfo]

        and: "mocked DataFetchingEnvironment"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "John Doe",
                "email": "john@example.com",
                "age": 30
        ]
        environment.getArgument("input") >> input

        when: "creating a new user"
        def mutationResolver = mutator.createCreateMutationResolver("users")
        def result = mutationResolver.get(environment)

        then: "should return the created user with generated id"
        result != null
        result.name == "John Doe"
        result.email == "john@example.com"
        result.age == 30
        result.id != null
        result.created_at != null
    }

    def "should handle create operation with null input"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(name: "users", columns: [], foreignKeys: [])
        schemaReflector.reflectSchema() >> ["users": tableInfo]

        and: "environment with null input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> null

        when: "attempting to create with null input"
        def mutationResolver = mutator.createCreateMutationResolver("users")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should handle create operation with all null values"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(name: "users", columns: [], foreignKeys: [])
        schemaReflector.reflectSchema() >> ["users": tableInfo]

        and: "environment with all null input values"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["name": null, "email": null]

        when: "attempting to create with all null values"
        def mutationResolver = mutator.createCreateMutationResolver("users")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should update existing record successfully"() {
        given: "a table with existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                price DECIMAL(10,2),
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.products (name, price) VALUES ('Old Product', 99.99)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "price", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "updated_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["products": tableInfo]

        and: "mocked DataFetchingEnvironment"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "id": 1,
                "name": "Updated Product",
                "price": 149.99
        ]
        environment.getArgument("input") >> input

        when: "updating the product"
        def mutationResolver = mutator.createUpdateMutationResolver("products")
        def result = mutationResolver.get(environment)

        then: "should return the updated product"
        result != null
        result.id == 1
        result.name == "Updated Product"
        result.price == 149.99
    }

    def "should handle update with missing primary key"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["products": tableInfo]

        and: "environment with no primary key in input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["name": "Updated Product"]

        when: "attempting to update without primary key"
        def mutationResolver = mutator.createUpdateMutationResolver("products")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should handle update with no update values"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["products": tableInfo]

        and: "environment with only primary key in input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["id": 1]

        when: "attempting to update with no update values"
        def mutationResolver = mutator.createUpdateMutationResolver("products")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should throw error when update of non-existent record"() {
        given: "a table without matching records"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.empty_products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "empty_products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["empty_products": tableInfo]

        and: "environment with non-existent id"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["id": 999, "name": "Non-existent"]

        when: "attempting to update non-existent record"
        def mutationResolver = mutator.createUpdateMutationResolver("empty_products")
        mutationResolver.get(environment)

        then: "should throw DataMutationException"
        def e = thrown(DataMutationException)
        e.message == "Error updating record: No record found with the specified primary key"
    }

    def "should delete record successfully"() {
        given: "a table with existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.items (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.items (name) VALUES ('Item to Delete')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "items",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["items": tableInfo]

        and: "mocked DataFetchingEnvironment"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("id") >> "1"

        when: "deleting the item"
        def mutationResolver = mutator.createDeleteMutationResolver("items")
        def result = mutationResolver.get(environment)

        then: "should return true"
        result == true
    }

    def "should handle delete with null id"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(name: "items", columns: [], foreignKeys: [])
        schemaReflector.reflectSchema() >> ["items": tableInfo]

        and: "environment with null id"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("id") >> null

        when: "attempting to delete with null id"
        def mutationResolver = mutator.createDeleteMutationResolver("items")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should handle delete of non-existent record"() {
        given: "an empty table"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.empty_items (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "empty_items",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["empty_items": tableInfo]

        and: "environment with non-existent id"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("id") >> "999"

        when: "attempting to delete non-existent record"
        def mutationResolver = mutator.createDeleteMutationResolver("empty_items")
        def result = mutationResolver.get(environment)

        then: "should return false"
        result == false
    }

    def "should handle table without primary key for delete"() {
        given: "mocked schema reflector for table without primary key"
        def tableInfo = new TableInfo(
                name: "no_pk_table",
                columns: [
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["no_pk_table": tableInfo]

        and: "environment with id"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("id") >> "1"

        when: "attempting to delete from table without primary key"
        def mutationResolver = mutator.createDeleteMutationResolver("no_pk_table")
        mutationResolver.get(environment)

        then: "should throw exception"
        thrown(Exception)
    }

    def "should perform bulk create successfully"() {
        given: "a table for bulk operations"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.bulk_users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(255)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "bulk_users",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "character varying(255)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["bulk_users": tableInfo]

        and: "environment with multiple inputs"
        def environment = Mock(DataFetchingEnvironment)
        def inputs = [
                ["name": "User 1", "email": "user1@example.com"],
                ["name": "User 2", "email": "user2@example.com"],
                ["name": "User 3", "email": "user3@example.com"]
        ]
        environment.getArgument("inputs") >> inputs

        when: "performing bulk create"
        def mutationResolver = mutator.createBulkCreateMutationResolver("bulk_users")
        def result = mutationResolver.get(environment)

        then: "should return all created users"
        result.size() == 3
        result.every { it.id != null && it.name != null }
        result[0].name == "User 1"
        result[1].name == "User 2"
        result[2].name == "User 3"
    }

    def "should handle bulk create with empty inputs"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(name: "bulk_users", columns: [], foreignKeys: [])
        schemaReflector.reflectSchema() >> ["bulk_users": tableInfo]

        and: "environment with empty inputs"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("inputs") >> []

        when: "attempting bulk create with empty inputs"
        def mutationResolver = mutator.createBulkCreateMutationResolver("bulk_users")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should handle bulk create with null inputs"() {
        given: "mocked schema reflector"
        def tableInfo = new TableInfo(name: "bulk_users", columns: [], foreignKeys: [])
        schemaReflector.reflectSchema() >> ["bulk_users": tableInfo]

        and: "environment with null inputs"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("inputs") >> null

        when: "attempting bulk create with null inputs"
        def mutationResolver = mutator.createBulkCreateMutationResolver("bulk_users")
        mutationResolver.get(environment)

        then: "should throw IllegalArgumentException"
        thrown(IllegalArgumentException)
    }

    def "should handle UUID type conversion in mutations"() {
        given: "a table with UUID column"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.uuid_table (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                name VARCHAR(100)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "uuid_table",
                columns: [
                        new ColumnInfo(name: "id", type: "uuid", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["uuid_table": tableInfo]

        and: "environment with UUID string"
        def environment = Mock(DataFetchingEnvironment)
        def uuid = UUID.randomUUID()
        environment.getArgument("input") >> ["id": uuid.toString(), "name": "Test"]

        when: "creating record with UUID"
        def mutationResolver = mutator.createCreateMutationResolver("uuid_table")
        def result = mutationResolver.get(environment)

        then: "should handle UUID conversion correctly"
        result != null
        result.id == uuid
        result.name == "Test"
    }

    def "should handle various data type conversions"() {
        given: "a table with mixed data types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.mixed_types_table (
                id SERIAL PRIMARY KEY,
                bigint_col BIGINT,
                decimal_col DECIMAL(10,2),
                bool_col BOOLEAN,
                timestamp_col TIMESTAMP
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "mixed_types_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "bigint_col", type: "bigint", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "decimal_col", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "bool_col", type: "boolean", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "timestamp_col", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["mixed_types_table": tableInfo]

        and: "environment with string representations of various types"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> [
                "bigint_col": "9223372036854775807",
                "decimal_col": "123.45",
                "bool_col": "true",
                "timestamp_col": "2023-01-01 12:00:00"
        ]

        when: "creating record with type conversions"
        def mutationResolver = mutator.createCreateMutationResolver("mixed_types_table")
        def result = mutationResolver.get(environment)

        then: "should handle all type conversions correctly"
        result != null
        result.bigint_col == 9223372036854775807L
        result.decimal_col == 123.45
        result.bool_col == true
        result.timestamp_col != null
    }

    def "should handle required timestamp fields"() {
        given: "a table with required timestamp columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.timestamp_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "timestamp_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "updated_at", type: "timestamp", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["timestamp_table": tableInfo]

        and: "environment without timestamp fields"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["name": "Test Record"]

        when: "creating record without required timestamps"
        def mutationResolver = mutator.createCreateMutationResolver("timestamp_table")
        def result = mutationResolver.get(environment)

        then: "should automatically add required timestamp fields"
        result != null
        result.name == "Test Record"
        result.created_at != null
        result.updated_at != null
    }

    def "should handle table not found scenarios"() {
        given: "mocked schema reflector returning empty map"
        schemaReflector.reflectSchema() >> [:]

        and: "environment with input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["name": "Test"]

        when: "attempting to create in non-existent table"
        def mutationResolver = mutator.createCreateMutationResolver("non_existent_table")
        mutationResolver.get(environment)

        then: "should throw NotFoundException"
        thrown(NotFoundException)
    }

    def "should handle table without primary key for update"() {
        given: "mocked schema reflector for table without primary key"
        def tableInfo = new TableInfo(
                name: "no_pk_update_table",
                columns: [
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "value", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["no_pk_update_table": tableInfo]

        and: "environment with update input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> ["name": "Updated", "value": 42]

        when: "attempting to update table without primary key"
        def mutationResolver = mutator.createUpdateMutationResolver("no_pk_update_table")
        mutationResolver.get(environment)

        then: "should throw NotFoundException"
        thrown(NotFoundException)
    }

    def "should handle create with relationships using connect"() {
        given: "tables with foreign key relationships"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.categories (
            id SERIAL PRIMARY KEY,
            name VARCHAR(100) NOT NULL
        )
    """)

        jdbcTemplate.execute("""
        CREATE TABLE test_schema.posts (
            id SERIAL PRIMARY KEY,
            title VARCHAR(200) NOT NULL,
            category_id INTEGER,
            FOREIGN KEY (category_id) REFERENCES test_schema.categories(id)
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.categories (name) VALUES ('Technology')
    """)

        and: "mocked schema reflector"
        def categoriesTableInfo = new TableInfo(
                name: "categories",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )

        def postsTableInfo = new TableInfo(
                name: "posts",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(200)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "category_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "category_id", referencedTable: "categories", referencedColumn: "id")
                ]
        )

        schemaReflector.reflectSchema() >> ["categories": categoriesTableInfo, "posts": postsTableInfo]

        and: "real transaction template"
        def realTransactionTemplate = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ))

        // Replace the mock with real transaction template
        mutator = new PostgresDatabaseMutatorImplement(
                jdbcTemplate,
                namedParameterJdbcTemplate,
                serviceLookup,
                appConfig,
                realTransactionTemplate
        )

        and: "environment with relationship connect input"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> [
                "title": "Tech Article",
                "categories_connect": ["id": 1]
        ]

        and: "verify initial state"
        def initialPostCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.posts", Integer.class)
        initialPostCount == 0

        when: "creating post with relationship connect"
        def mutationResolver = mutator.createCreateWithRelationshipsMutationResolver("posts")
        def result = mutationResolver.get(environment)

        then: "should create post with connected category"
        result != null
        result.title == "Tech Article"
        result.category_id == 1
        result.id != null

        and: "verify the post was actually created in database"
        def finalPostCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.posts", Integer.class)
        finalPostCount == 1

        def createdPost = jdbcTemplate.queryForMap("SELECT * FROM test_schema.posts WHERE id = ?", result.id)
        createdPost.title == "Tech Article"
        createdPost.category_id == 1
    }

    def "should handle transaction rollback on error"() {
        given: "tables with constraints for testing rollback"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.categories_real (
            id SERIAL PRIMARY KEY,
            name VARCHAR(100) NOT NULL UNIQUE
        )
    """)

        jdbcTemplate.execute("""
        CREATE TABLE test_schema.posts_real (
            id SERIAL PRIMARY KEY,
            title VARCHAR(200) NOT NULL,
            category_id INTEGER NOT NULL,
            FOREIGN KEY (category_id) REFERENCES test_schema.categories_real(id)
        )
    """)

        // Insert a category that we'll reference
        jdbcTemplate.execute("""
        INSERT INTO test_schema.categories_real (name) VALUES ('Tech')
    """)

        and: "schema reflector setup"
        def categoriesTableInfo = new TableInfo(
                name: "categories_real",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )

        def postsTableInfo = new TableInfo(
                name: "posts_real",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(200)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "category_id", type: "integer", primaryKey: false, nullable: false)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "category_id", referencedTable: "categories_real", referencedColumn: "id")
                ]
        )

        schemaReflector.reflectSchema() >> ["categories_real": categoriesTableInfo, "posts_real": postsTableInfo]

        and: "transaction template"
        def realTransactionTemplate = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        ))

        mutator = new PostgresDatabaseMutatorImplement(
                jdbcTemplate,
                namedParameterJdbcTemplate,
                serviceLookup,
                appConfig,
                realTransactionTemplate
        )

        and: "environment with input that will cause constraint violation"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> [
                "title": "Test Post",
                "categories_real_create": [
                        "name": "Tech" // This will cause UNIQUE constraint violation
                ]
        ]

        and: "verify initial state - only one category exists"
        def initialCategoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.categories_real", Integer.class)
        def initialPostCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.posts_real", Integer.class)

        initialCategoryCount == 1
        initialPostCount == 0

        when: "creating with relationships that will fail due to constraint violation"
        def mutationResolver = mutator.createCreateWithRelationshipsMutationResolver("posts_real")
        mutationResolver.get(environment)

        then: "should throw DataMutationException and rollback transaction"
        thrown(DataMutationException)

        and: "verify rollback occurred - no new records should be created"
        def finalCategoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.categories_real", Integer.class)
        def finalPostCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.posts_real", Integer.class)

        finalCategoryCount == 1
        finalPostCount == 0
    }
}