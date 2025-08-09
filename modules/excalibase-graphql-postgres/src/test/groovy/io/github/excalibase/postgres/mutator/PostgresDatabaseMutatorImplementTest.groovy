package io.github.excalibase.postgres.mutator

import graphql.schema.DataFetchingEnvironment
import io.github.excalibase.config.AppConfig
import io.github.excalibase.constant.DatabaseType
import io.github.excalibase.constant.SupportedDatabaseConstant
import io.github.excalibase.exception.DataMutationException
import io.github.excalibase.exception.NotFoundException
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
import io.github.excalibase.model.TableInfo
import io.github.excalibase.postgres.mutator.PostgresDatabaseMutatorImplement
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
        environment.getArgument("input") >> [id: "1"]

        when: "deleting the item"
        def mutationResolver = mutator.createDeleteMutationResolver("items")
        def result = mutationResolver.get(environment)

        then: "should return deleted record"
        result.id == 1
        result.name == "Item to Delete"
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
        environment.getArgument("input") >> [id: "999"]

        when: "attempting to delete non-existent record"
        def mutationResolver = mutator.createDeleteMutationResolver("empty_items")
        mutationResolver.get(environment)
        
        then: "should throw NotFoundException"
        thrown(NotFoundException)
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

    // ========== Enhanced PostgreSQL Types Mutation Tests ==========

    def "should create records with JSON and JSONB types"() {
        given: "a table with JSON and JSONB columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.json_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                json_data JSON,
                jsonb_data JSONB
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "json_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "json_data", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "jsonb_data", type: "jsonb", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["json_records": tableInfo]

        and: "environment with JSON data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "JSON Record",
                "json_data": '{"name": "John", "age": 30, "active": true}',
                "jsonb_data": '{"tags": ["developer", "java"], "score": 95.5}'
        ]
        environment.getArgument("input") >> input

        when: "creating record with JSON/JSONB data"
        def mutationResolver = mutator.createCreateMutationResolver("json_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with JSON data"
        result != null
        result.name == "JSON Record"
        result.json_data != null
        result.jsonb_data != null
        result.id != null
    }

    def "should create records with interval types"() {
        given: "a table with interval columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.interval_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                duration INTERVAL,
                wait_time INTERVAL
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "interval_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "duration", type: "interval", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "wait_time", type: "interval", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["interval_records": tableInfo]

        and: "environment with interval data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Task Record",
                "duration": "2 days 3 hours",
                "wait_time": "30 minutes"
        ]
        environment.getArgument("input") >> input

        when: "creating record with interval data"
        def mutationResolver = mutator.createCreateMutationResolver("interval_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with interval data"
        result != null
        result.name == "Task Record"
        result.duration != null
        result.wait_time != null
        result.id != null
    }

    def "should create records with network types"() {
        given: "a table with network columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.network_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                ip_address INET,
                subnet CIDR,
                mac_address MACADDR
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "network_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "ip_address", type: "inet", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "subnet", type: "cidr", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "mac_address", type: "macaddr", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["network_records": tableInfo]

        and: "environment with network data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Network Device",
                "ip_address": "192.168.1.100",
                "subnet": "192.168.0.0/24",
                "mac_address": "08:00:27:12:34:56"
        ]
        environment.getArgument("input") >> input

        when: "creating record with network data"
        def mutationResolver = mutator.createCreateMutationResolver("network_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with network data"
        result != null
        result.name == "Network Device"
        result.ip_address != null
        result.subnet != null
        result.mac_address != null
        result.id != null
    }

    def "should create records with enhanced datetime types"() {
        given: "a table with enhanced datetime columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.datetime_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                event_time TIMESTAMPTZ,
                local_time TIMETZ
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "datetime_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "event_time", type: "timestamptz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "local_time", type: "timetz", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["datetime_records": tableInfo]

        and: "environment with enhanced datetime data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Event Record",
                "event_time": "2023-01-15T10:30:00+00:00",
                "local_time": "14:30:00+00"
        ]
        environment.getArgument("input") >> input

        when: "creating record with enhanced datetime data"
        def mutationResolver = mutator.createCreateMutationResolver("datetime_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with datetime data"
        result != null
        result.name == "Event Record"
        result.event_time != null
        result.local_time != null
        result.id != null
    }

    def "should create records with numeric precision types"() {
        given: "a table with numeric precision columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.numeric_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                price NUMERIC(10,2),
                cost NUMERIC(8,2)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "numeric_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "cost", type: "numeric", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["numeric_records": tableInfo]

        and: "environment with numeric data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Product Record",
                "price": "1234.56",
                "cost": "999.99"
        ]
        environment.getArgument("input") >> input

        when: "creating record with numeric data"
        def mutationResolver = mutator.createCreateMutationResolver("numeric_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with numeric data"
        result != null
        result.name == "Product Record"
        result.price == 1234.56
        result.cost != null
        result.id != null
    }

    def "should create records with binary and XML types"() {
        given: "a table with binary and XML columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.binary_xml_records (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                binary_data BYTEA,
                xml_data XML
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "binary_xml_records",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "binary_data", type: "bytea", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "xml_data", type: "xml", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["binary_xml_records": tableInfo]

        and: "environment with binary and XML data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Binary XML Record",
                "binary_data": "Hello World", // Will be converted to bytea
                "xml_data": "<person><name>John</name><age>30</age></person>"
        ]
        environment.getArgument("input") >> input

        when: "creating record with binary and XML data"
        def mutationResolver = mutator.createCreateMutationResolver("binary_xml_records")
        def result = mutationResolver.get(environment)

        then: "should return created record with binary and XML data"
        result != null
        result.name == "Binary XML Record"
        result.binary_data != null
        result.xml_data != null
        result.id != null
    }

    def "should update records with enhanced PostgreSQL types"() {
        given: "a table with enhanced types and existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.enhanced_updates (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                json_data JSON,
                interval_data INTERVAL,
                ip_address INET,
                price NUMERIC(10,2)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.enhanced_updates (name, json_data, interval_data, ip_address, price) 
            VALUES ('Original Record', '{"status": "draft"}', '1 hour', '192.168.1.1', 100.00)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "enhanced_updates",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "json_data", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "interval_data", type: "interval", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "ip_address", type: "inet", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["enhanced_updates": tableInfo]

        and: "environment with update data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "id": 1,
                "name": "Updated Record",
                "json_data": '{"status": "published", "views": 150}',
                "interval_data": "2 days 3 hours",
                "ip_address": "10.0.0.1",
                "price": "299.99"
        ]
        environment.getArgument("input") >> input

        when: "updating record with enhanced types"
        def mutationResolver = mutator.createUpdateMutationResolver("enhanced_updates")
        def result = mutationResolver.get(environment)

        then: "should return updated record with enhanced type data"
        result != null
        result.id == 1
        result.name == "Updated Record"
        result.json_data != null
        result.interval_data != null
        result.ip_address != null
        result.price == 299.99
    }

    def "should bulk create records with enhanced types"() {
        given: "a table with enhanced type columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.enhanced_bulk (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                json_metadata JSON,
                created_time TIMESTAMPTZ
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "enhanced_bulk",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "json_metadata", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_time", type: "timestamptz", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["enhanced_bulk": tableInfo]

        and: "environment with multiple enhanced type inputs"
        def environment = Mock(DataFetchingEnvironment)
        def inputs = [
                [
                        "name": "Record 1",
                        "json_metadata": '{"category": "electronics", "featured": true}',
                        "created_time": "2023-01-15T10:30:00+00:00"
                ],
                [
                        "name": "Record 2",
                        "json_metadata": '{"category": "books", "featured": false}',
                        "created_time": "2023-02-20T15:45:00+00:00"
                ],
                [
                        "name": "Record 3",
                        "json_metadata": '{"category": "electronics", "featured": true}',
                        "created_time": "2023-03-25T20:00:00+00:00"
                ]
        ]
        environment.getArgument("inputs") >> inputs

        when: "performing bulk create with enhanced types"
        def mutationResolver = mutator.createBulkCreateMutationResolver("enhanced_bulk")
        def result = mutationResolver.get(environment)

        then: "should return all created records with enhanced type data"
        result.size() == 3
        result.every { it.id != null && it.name != null }
        result.every { it.json_metadata != null && it.created_time != null }
        result[0].name == "Record 1"
        result[1].name == "Record 2"
        result[2].name == "Record 3"
    }

    def "should handle complex enhanced type mutations with validation"() {
        given: "a table with all enhanced PostgreSQL types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.complex_enhanced (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                json_col JSON,
                jsonb_col JSONB,
                interval_col INTERVAL,
                timestamptz_col TIMESTAMPTZ,
                timetz_col TIMETZ,
                numeric_col NUMERIC(10,2),
                inet_col INET,
                cidr_col CIDR,
                macaddr_col MACADDR,
                bytea_col BYTEA,
                xml_col XML
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "complex_enhanced",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "json_col", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "jsonb_col", type: "jsonb", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "interval_col", type: "interval", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "timestamptz_col", type: "timestamptz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "timetz_col", type: "timetz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "numeric_col", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "inet_col", type: "inet", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "cidr_col", type: "cidr", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "macaddr_col", type: "macaddr", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "bytea_col", type: "bytea", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "xml_col", type: "xml", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["complex_enhanced": tableInfo]

        and: "environment with comprehensive enhanced type data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Complex Enhanced Record",
                "json_col": '{"name": "John", "age": 30, "city": "New York"}',
                "jsonb_col": '{"score": 95, "tags": ["developer", "java"], "active": true}',
                "interval_col": "2 days 3 hours",
                "timestamptz_col": "2023-01-15T10:30:00+00:00",
                "timetz_col": "14:30:00+00",
                "numeric_col": "1234.56",
                "inet_col": "192.168.1.1",
                "cidr_col": "192.168.0.0/24",
                "macaddr_col": "08:00:27:00:00:00",
                "bytea_col": "Hello World",
                "xml_col": "<person><name>John</name><age>30</age></person>"
        ]
        environment.getArgument("input") >> input

        when: "creating record with all enhanced types"
        def mutationResolver = mutator.createCreateMutationResolver("complex_enhanced")
        def result = mutationResolver.get(environment)

        then: "should return created record with all enhanced type data properly handled"
        result != null
        result.name == "Complex Enhanced Record"
        result.json_col != null
        result.jsonb_col != null
        result.interval_col != null
        result.timestamptz_col != null
        result.timetz_col != null
        result.numeric_col == 1234.56
        result.inet_col != null
        result.cidr_col != null
        result.macaddr_col != null
        result.bytea_col != null
        result.xml_col != null
        result.id != null
    }

    def "should handle enhanced type conversion errors gracefully"() {
        given: "a table with enhanced types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.enhanced_errors (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                interval_col INTERVAL,
                inet_col INET
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "enhanced_errors",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "interval_col", type: "interval", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "inet_col", type: "inet", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["enhanced_errors": tableInfo]

        and: "environment with invalid enhanced type data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Error Record",
                "interval_col": "invalid-interval-format",
                "inet_col": "not-an-ip-address"
        ]
        environment.getArgument("input") >> input

        when: "creating record with invalid enhanced type data"
        def mutationResolver = mutator.createCreateMutationResolver("enhanced_errors")
        mutationResolver.get(environment)

        then: "should throw DataMutationException for invalid data"
        thrown(DataMutationException)
    }

    // ========== PostgreSQL Array Types Mutation Tests ==========

    def "should create records with integer array types"() {
        given: "a table with integer array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.integer_array_mutations (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                int_array INTEGER[],
                bigint_array BIGINT[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "integer_array_mutations",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "bigint_array", type: "bigint[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["integer_array_mutations": tableInfo]

        and: "environment with array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Array Record",
                "int_array": [1, 2, 3, 4, 5],
                "bigint_array": [100, 200, 300]
        ]
        environment.getArgument("input") >> input

        when: "creating record with integer arrays"
        def mutationResolver = mutator.createCreateMutationResolver("integer_array_mutations")
        def result = mutationResolver.get(environment)

        then: "should return created record with array data"
        result != null
        result.name == "Array Record"
        result.int_array != null
        result.bigint_array != null
        result.id != null

        and: "verify database content"
        def dbRecord = jdbcTemplate.queryForMap("SELECT * FROM test_schema.integer_array_mutations WHERE id = ?", result.id)
        dbRecord.name == "Array Record"
    }

    def "should create records with text array types"() {
        given: "a table with text array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.text_array_mutations (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                text_array TEXT[],
                varchar_array VARCHAR(50)[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "text_array_mutations",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "varchar_array", type: "character varying[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["text_array_mutations": tableInfo]

        and: "environment with text array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Text Array Record",
                "text_array": ["apple", "banana", "cherry"],
                "varchar_array": ["red", "green", "blue"]
        ]
        environment.getArgument("input") >> input

        when: "creating record with text arrays"
        def mutationResolver = mutator.createCreateMutationResolver("text_array_mutations")
        def result = mutationResolver.get(environment)

        then: "should return created record with text array data"
        result != null
        result.name == "Text Array Record"
        result.text_array != null
        result.varchar_array != null
        result.id != null

        and: "verify database content"
        def dbRecord = jdbcTemplate.queryForMap("SELECT * FROM test_schema.text_array_mutations WHERE id = ?", result.id)
        dbRecord.name == "Text Array Record"
    }

    def "should create records with boolean and numeric array types"() {
        given: "a table with boolean and numeric array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.mixed_array_mutations (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                bool_array BOOLEAN[],
                decimal_array DECIMAL(5,2)[],
                float_array FLOAT[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "mixed_array_mutations",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "bool_array", type: "boolean[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "decimal_array", type: "decimal[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "float_array", type: "float[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["mixed_array_mutations": tableInfo]

        and: "environment with mixed array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Mixed Arrays",
                "bool_array": [true, false, true],
                "decimal_array": [10.50, 20.75, 30.25],
                "float_array": [1.1, 2.2, 3.3]
        ]
        environment.getArgument("input") >> input

        when: "creating record with mixed arrays"
        def mutationResolver = mutator.createCreateMutationResolver("mixed_array_mutations")
        def result = mutationResolver.get(environment)

        then: "should return created record with mixed array data"
        result != null
        result.name == "Mixed Arrays"
        result.bool_array != null
        result.decimal_array != null
        result.float_array != null
        result.id != null
    }

    def "should create records with empty and null arrays"() {
        given: "a table with array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.null_array_mutations (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                int_array INTEGER[],
                text_array TEXT[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "null_array_mutations",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["null_array_mutations": tableInfo]

        when: "creating record with empty arrays"
        def environment1 = Mock(DataFetchingEnvironment)
        environment1.getArgument("input") >> [
                "name": "Empty Arrays",
                "int_array": [],
                "text_array": []
        ]
        def mutationResolver = mutator.createCreateMutationResolver("null_array_mutations")
        def result1 = mutationResolver.get(environment1)

        then: "should handle empty arrays correctly"
        result1 != null
        result1.name == "Empty Arrays"
        result1.id != null

        when: "creating record with null arrays"
        def environment2 = Mock(DataFetchingEnvironment)
        environment2.getArgument("input") >> [
                "name": "Null Arrays",
                "int_array": null,
                "text_array": null
        ]
        def result2 = mutationResolver.get(environment2)

        then: "should handle null arrays correctly"
        result2 != null
        result2.name == "Null Arrays"
        result2.id != null

        when: "creating record with single element arrays"
        def environment3 = Mock(DataFetchingEnvironment)
        environment3.getArgument("input") >> [
                "name": "Single Element Arrays",
                "int_array": [42],
                "text_array": ["single"]
        ]
        def result3 = mutationResolver.get(environment3)

        then: "should handle single element arrays correctly"
        result3 != null
        result3.name == "Single Element Arrays"
        result3.id != null
    }

    def "should update records with array types"() {
        given: "a table with array columns and existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.array_updates (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                int_array INTEGER[],
                text_array TEXT[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.array_updates (name, int_array, text_array) 
            VALUES ('Original', '{1,2,3}', '{"old","values"}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "array_updates",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["array_updates": tableInfo]

        and: "environment with updated array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "id": 1,
                "name": "Updated Arrays",
                "int_array": [10, 20, 30, 40],
                "text_array": ["new", "updated", "values"]
        ]
        environment.getArgument("input") >> input

        when: "updating record with array data"
        def mutationResolver = mutator.createUpdateMutationResolver("array_updates")
        def result = mutationResolver.get(environment)

        then: "should return updated record with new array data"
        result != null
        result.id == 1
        result.name == "Updated Arrays"
        result.int_array != null
        result.text_array != null

        and: "verify database was updated"
        def dbRecord = jdbcTemplate.queryForMap("SELECT * FROM test_schema.array_updates WHERE id = 1")
        dbRecord.name == "Updated Arrays"
    }

    def "should bulk create records with array types"() {
        given: "a table with array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.bulk_array_mutations (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                int_array INTEGER[],
                text_array TEXT[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "bulk_array_mutations",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["bulk_array_mutations": tableInfo]

        and: "environment with multiple array inputs"
        def environment = Mock(DataFetchingEnvironment)
        def inputs = [
                [
                        "name": "Bulk Record 1",
                        "int_array": [1, 2, 3],
                        "text_array": ["a", "b", "c"]
                ],
                [
                        "name": "Bulk Record 2",
                        "int_array": [4, 5, 6],
                        "text_array": ["d", "e", "f"]
                ],
                [
                        "name": "Bulk Record 3",
                        "int_array": [],
                        "text_array": ["single"]
                ]
        ]
        environment.getArgument("inputs") >> inputs

        when: "performing bulk create with arrays"
        def mutationResolver = mutator.createBulkCreateMutationResolver("bulk_array_mutations")
        def result = mutationResolver.get(environment)

        then: "should return all created records with array data"
        result.size() == 3
        result.every { it.id != null && it.name != null }
        result[0].name == "Bulk Record 1"
        result[1].name == "Bulk Record 2"
        result[2].name == "Bulk Record 3"

        and: "verify database contains all records"
        def dbRecordCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.bulk_array_mutations", Integer.class)
        dbRecordCount == 3
    }

    def "should handle arrays with special characters in mutations"() {
        given: "a table with text array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.special_char_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                special_array TEXT[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "special_char_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "special_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["special_char_arrays": tableInfo]

        and: "environment with special character array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Special Characters",
                "special_array": ["hello, world", "it's working", "path\\to\\file", "quote\"test"]
        ]
        environment.getArgument("input") >> input

        when: "creating record with special character arrays"
        def mutationResolver = mutator.createCreateMutationResolver("special_char_arrays")
        def result = mutationResolver.get(environment)

        then: "should handle special characters correctly"
        result != null
        result.name == "Special Characters"
        result.special_array != null
        result.id != null

        and: "verify database content"
        def dbRecord = jdbcTemplate.queryForMap("SELECT * FROM test_schema.special_char_arrays WHERE id = ?", result.id)
        dbRecord.name == "Special Characters"
    }

    def "should create records with mixed enhanced types including arrays"() {
        given: "a table with both enhanced types and arrays"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.mixed_enhanced_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                json_data JSON,
                int_array INTEGER[],
                text_array TEXT[],
                timestamptz_col TIMESTAMPTZ,
                inet_col INET
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "mixed_enhanced_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "json_data", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "timestamptz_col", type: "timestamptz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "inet_col", type: "inet", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["mixed_enhanced_arrays": tableInfo]

        and: "environment with mixed enhanced type and array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Mixed Enhanced Record",
                "json_data": '{"tags": ["json", "array"], "active": true}',
                "int_array": [1, 2, 3, 4, 5],
                "text_array": ["apple", "banana", "cherry"],
                "timestamptz_col": "2023-01-15T10:30:00+00:00",
                "inet_col": "192.168.1.1"
        ]
        environment.getArgument("input") >> input

        when: "creating record with mixed enhanced types and arrays"
        def mutationResolver = mutator.createCreateMutationResolver("mixed_enhanced_arrays")
        def result = mutationResolver.get(environment)

        then: "should return created record with all data types properly handled"
        result != null
        result.name == "Mixed Enhanced Record"
        result.json_data != null
        result.int_array != null
        result.text_array != null
        result.timestamptz_col != null
        result.inet_col != null
        result.id != null
    }

    def "should handle array mutation errors gracefully"() {
        given: "a table with strongly typed array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.array_errors (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                int_array INTEGER[]
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "array_errors",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["array_errors": tableInfo]

        and: "environment with invalid array data"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "name": "Invalid Array",
                "int_array": ["not", "integers"]  // Invalid - strings in integer array
        ]
        environment.getArgument("input") >> input

        when: "creating record with invalid array data"
        def mutationResolver = mutator.createCreateMutationResolver("array_errors")
        mutationResolver.get(environment)

        then: "should throw DataMutationException for invalid array data"
        thrown(DataMutationException)
    }

    def "should create customer record with all fields"() {
        given: "a customer table like in e2e tests"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.customer (
                customer_id SERIAL PRIMARY KEY,
                first_name VARCHAR(45) NOT NULL,
                last_name VARCHAR(45) NOT NULL,
                email VARCHAR(50),
                active BOOLEAN DEFAULT true,
                create_date DATE NOT NULL DEFAULT CURRENT_DATE,
                last_update TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "customer",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "varchar", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "active", type: "boolean", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "create_date", type: "date", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_update", type: "timestamp", primaryKey: false, nullable: false)
                ],
                foreignKeys: [],
                view: false
        )
        schemaReflector.reflectSchema() >> ["customer": tableInfo]

        when: "creating a customer record"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "first_name": "John",
                "last_name": "Doe",
                "email": "john.doe@example.com",
                "active": true
        ]
        environment.getArgument("input") >> input
        def createResolver = mutator.createCreateMutationResolver("customer")
        def result = createResolver.get(environment)

        then: "should return created customer with generated ID"
        result != null
        result.customer_id != null
        result.first_name == "John"
        result.last_name == "Doe"
        result.email == "john.doe@example.com"
        result.active == true
        result.create_date != null
        result.last_update != null
    }
    
    def "should update customer record properly"() {
        given: "a customer table with existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.update_customer (
                customer_id SERIAL PRIMARY KEY,
                first_name VARCHAR(45) NOT NULL,
                last_name VARCHAR(45) NOT NULL,
                email VARCHAR(50),
                active BOOLEAN DEFAULT true
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.update_customer (customer_id, first_name, last_name, email, active) 
            VALUES (1, 'Jane', 'Smith', 'jane.smith@example.com', true)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "update_customer",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "varchar", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "active", type: "boolean", primaryKey: false, nullable: true)
                ],
                foreignKeys: [],
                view: false
        )
        schemaReflector.reflectSchema() >> ["update_customer": tableInfo]

        when: "updating the customer record"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "customer_id": 1,
                "first_name": "Jane",
                "last_name": "Doe",
                "email": "jane.doe@example.com",
                "active": false
        ]
        environment.getArgument("input") >> input
        def updateResolver = mutator.createUpdateMutationResolver("update_customer")
        def result = updateResolver.get(environment)

        then: "should return updated customer"
        result != null
        result.customer_id == 1
        result.first_name == "Jane"
        result.last_name == "Doe"
        result.email == "jane.doe@example.com"
        result.active == false
    }
    
    def "should delete customer record properly"() {
        given: "a customer table with existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.delete_customer (
                customer_id SERIAL PRIMARY KEY,
                first_name VARCHAR(45) NOT NULL,
                last_name VARCHAR(45) NOT NULL
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.delete_customer (customer_id, first_name, last_name) 
            VALUES (1, 'ToDelete', 'User')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "delete_customer",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false)
                ],
                foreignKeys: [],
                view: false
        )
        schemaReflector.reflectSchema() >> ["delete_customer": tableInfo]

        when: "deleting the customer record"
        def environment = Mock(DataFetchingEnvironment)
        environment.getArgument("input") >> [customer_id: "1"]
        def deleteResolver = mutator.createDeleteMutationResolver("delete_customer")
        def result = deleteResolver.get(environment)

        then: "should return deleted record"
        result.customer_id == 1
        result.first_name == "ToDelete"
        result.last_name == "User"
        
        and: "record should be deleted from database"
        def count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM test_schema.delete_customer WHERE customer_id = 1", 
            Integer.class
        )
        count == 0
    }
    
    def "should create records with relationships"() {
        given: "tables with foreign key relationships"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.rel_customers (
                customer_id SERIAL PRIMARY KEY,
                first_name VARCHAR(45) NOT NULL,
                last_name VARCHAR(45) NOT NULL
            )
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.rel_orders (
                order_id SERIAL PRIMARY KEY,
                customer_id INTEGER REFERENCES test_schema.rel_customers(customer_id),
                total_amount NUMERIC(10,2) NOT NULL,
                status VARCHAR(20) DEFAULT 'pending'
            )
        """)
        
        // Insert existing customer
        jdbcTemplate.execute("""
            INSERT INTO test_schema.rel_customers (customer_id, first_name, last_name) 
            VALUES (1, 'Existing', 'Customer')
        """)

        and: "mocked schema reflector with relationship info"
        def customerTableInfo = new TableInfo(
                name: "rel_customers",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false)
                ],
                foreignKeys: [],
                view: false
        )
        
        def orderTableInfo = new TableInfo(
                name: "rel_orders",
                columns: [
                        new ColumnInfo(name: "order_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "total_amount", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "status", type: "varchar", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo("customer_id", "rel_customers", "customer_id")
                ],
                view: false
        )
        
        schemaReflector.reflectSchema() >> [
            "rel_customers": customerTableInfo,
            "rel_orders": orderTableInfo
        ]

        and: "transaction template is configured to execute the callback"
        transactionTemplate.execute(_) >> { args ->
            def callback = args[0]
            return callback.doInTransaction(null)
        }

        when: "creating order with relationship to existing customer"
        def environment = Mock(DataFetchingEnvironment)
        def input = [
                "total_amount": 299.99,
                "status": "pending",
                "rel_customers_connect": ["id": 1]
        ]
        environment.getArgument("input") >> input
        def createResolver = mutator.createCreateWithRelationshipsMutationResolver("rel_orders")
        def result = createResolver.get(environment)

        then: "should return created order with customer relationship"
        result != null
        result.order_id != null
        result.customer_id == 1
        result.total_amount == 299.99
        result.status == "pending"
    }
    
    def "should create bulk customer records"() {
        given: "a customer table"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.bulk_customers (
                customer_id SERIAL PRIMARY KEY,
                first_name VARCHAR(45) NOT NULL,
                last_name VARCHAR(45) NOT NULL,
                email VARCHAR(50)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "bulk_customers",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "varchar", primaryKey: false, nullable: true)
                ],
                foreignKeys: [],
                view: false
        )
        schemaReflector.reflectSchema() >> ["bulk_customers": tableInfo]

        when: "creating multiple customer records"
        def environment = Mock(DataFetchingEnvironment)
        def inputs = [
                [
                        "first_name": "John",
                        "last_name": "Doe",
                        "email": "john@example.com"
                ],
                [
                        "first_name": "Jane",
                        "last_name": "Smith",
                        "email": "jane@example.com"
                ],
                [
                        "first_name": "Bob",
                        "last_name": "Johnson",
                        "email": "bob@example.com"
                ]
        ]
        environment.getArgument("inputs") >> inputs
        def bulkCreateResolver = mutator.createBulkCreateMutationResolver("bulk_customers")
        def results = bulkCreateResolver.get(environment)

        then: "should return all created customer records"
        results != null
        results.size() == 3
        results[0].customer_id != null
        results[0].first_name == "John"
        results[0].last_name == "Doe"
        results[1].first_name == "Jane"
        results[1].last_name == "Smith"
        results[2].first_name == "Bob"
        results[2].last_name == "Johnson"
        
        and: "all records should have unique IDs"
        def ids = results.collect { it.customer_id }
        ids.unique().size() == 3
    }

    // TDD RED PHASE: Custom Type Mutation Tests
    def "should create records with custom enum values"() {
        given: "a table with custom enum column"
        jdbcTemplate.execute("""
            CREATE TYPE test_status AS ENUM ('draft', 'published', 'archived')
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_documents (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100),
                status test_status
            )
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("title", "character varying", false, true),
            new ColumnInfo("status", "test_status", false, true)
        ]
        def tableInfo = new TableInfo("test_documents", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_documents": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                title: "Test Document",
                status: "published"
            ]
        }

        when: "creating a record with custom enum value"
        def mutationResolver = mutator.createCreateMutationResolver("test_documents")
        def result = mutationResolver.get(environment)

        then: "should create record with proper enum value"
        result.title == "Test Document"
        result.status == "published"
        result.id != null

        and: "should be persisted in database correctly"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.test_documents WHERE title = ?", "Test Document")
        dbResults.size() == 1
        dbResults[0].status == "published"

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_documents")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_status")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should create records with custom composite values"() {
        given: "a table with custom composite column"
        jdbcTemplate.execute("""
            CREATE TYPE test_contact AS (
                email VARCHAR(100),
                phone VARCHAR(20)
            )
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                contact_info test_contact
            )
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying", false, true),
            new ColumnInfo("contact_info", "test_contact", false, true)
        ]
        def tableInfo = new TableInfo("test_users", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_users": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                name: "John Doe",
                contact_info: [
                    email: "john@example.com",
                    phone: "555-1234"
                ]
            ]
        }

        when: "creating a record with custom composite value"
        def mutationResolver = mutator.createCreateMutationResolver("test_users")
        def result = mutationResolver.get(environment)

        then: "should create record with proper composite value"
        result.name == "John Doe"
        result.contact_info != null
        result.contact_info.toString().contains("john@example.com")
        result.contact_info.toString().contains("555-1234")
        result.id != null

        and: "should be persisted in database correctly"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.test_users WHERE name = ?", "John Doe")
        dbResults.size() == 1
        // Database should store composite as string representation
        dbResults[0].contact_info.toString().contains("john@example.com")
        dbResults[0].contact_info.toString().contains("555-1234")

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_users")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_contact")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should update records with custom enum values"() {
        given: "a table with custom enum column and existing data"
        jdbcTemplate.execute("""
            CREATE TYPE test_priority AS ENUM ('low', 'medium', 'high', 'urgent')
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_issues (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100),
                priority test_priority
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_issues (title, priority) 
            VALUES ('Test Issue', 'low')
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("title", "character varying", false, true),
            new ColumnInfo("priority", "test_priority", false, true)
        ]
        def tableInfo = new TableInfo("test_issues", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_issues": tableInfo]

        // Get the created record ID
        def existingRecord = jdbcTemplate.queryForMap("SELECT * FROM test_schema.test_issues WHERE title = ?", "Test Issue")
        def recordId = existingRecord.id

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                id: recordId,
                priority: "urgent"
            ]
        }

        when: "updating a record with custom enum value"
        def mutationResolver = mutator.createUpdateMutationResolver("test_issues")
        def result = mutationResolver.get(environment)

        then: "should update record with proper enum value"
        result.title == "Test Issue"
        result.priority == "urgent"
        result.id == recordId

        and: "should be persisted in database correctly"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.test_issues WHERE id = ?", recordId)
        dbResults.size() == 1
        dbResults[0].priority == "urgent"

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_issues")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_priority")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    // ==========================================
    // COMPOSITE KEY MUTATION TESTS - TDD APPROACH
    // ==========================================

    def "should create record with composite primary key"() {
        given: "a table with composite primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_key_table (
                key1 INTEGER NOT NULL,
                key2 INTEGER NOT NULL,
                data VARCHAR(100),
                PRIMARY KEY (key1, key2)
            )
        """)

        def columns = [
            new ColumnInfo("key1", "integer", true, false),
            new ColumnInfo("key2", "integer", true, false),
            new ColumnInfo("data", "character varying", false, true)
        ]
        def tableInfo = new TableInfo("composite_key_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_key_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                key1: 1,
                key2: 2,
                data: "Test Data"
            ]
        }

        when: "creating a record with composite primary key"
        def mutationResolver = mutator.createCreateMutationResolver("composite_key_table")
        def result = mutationResolver.get(environment)

        then: "should create record with both primary key parts"
        result.key1 == 1
        result.key2 == 2
        result.data == "Test Data"

        and: "should be persisted in database correctly"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.composite_key_table WHERE key1 = ? AND key2 = ?", 1, 2)
        dbResults.size() == 1
        dbResults[0].key1 == 1
        dbResults[0].key2 == 2
        dbResults[0].data == "Test Data"

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_key_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should update record using composite primary key"() {
        given: "a table with composite primary key and existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_update_table (
                order_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                quantity INTEGER,
                price DECIMAL(10,2),
                PRIMARY KEY (order_id, product_id)
            )
        """)

        // Insert test data
        jdbcTemplate.execute("""
            INSERT INTO test_schema.composite_update_table (order_id, product_id, quantity, price) 
            VALUES (1, 100, 5, 99.99)
        """)

        def columns = [
            new ColumnInfo("order_id", "integer", true, false),
            new ColumnInfo("product_id", "integer", true, false),
            new ColumnInfo("quantity", "integer", false, true),
            new ColumnInfo("price", "numeric", false, true)
        ]
        def tableInfo = new TableInfo("composite_update_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_update_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                order_id: 1,      // Required for composite key identification
                product_id: 100,  // Required for composite key identification
                quantity: 10,     // Updated value
                price: 149.99     // Updated value
            ]
        }

        when: "updating record using composite primary key"
        def mutationResolver = mutator.createUpdateMutationResolver("composite_update_table")
        def result = mutationResolver.get(environment)

        then: "should update record correctly"
        result.order_id == 1
        result.product_id == 100
        result.quantity == 10
        result.price == 149.99

        and: "should be updated in database"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.composite_update_table WHERE order_id = ? AND product_id = ?", 1, 100)
        dbResults.size() == 1
        dbResults[0].quantity == 10
        dbResults[0].price == 149.99

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_update_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should delete record using composite primary key"() {
        given: "a table with composite primary key and existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_delete_table (
                customer_id INTEGER NOT NULL,
                order_id INTEGER NOT NULL,
                status VARCHAR(20),
                PRIMARY KEY (customer_id, order_id)
            )
        """)

        // Insert test data
        jdbcTemplate.execute("""
            INSERT INTO test_schema.composite_delete_table (customer_id, order_id, status) 
            VALUES (1, 101, 'pending'), (1, 102, 'shipped'), (2, 101, 'delivered')
        """)

        def columns = [
            new ColumnInfo("customer_id", "integer", true, false),
            new ColumnInfo("order_id", "integer", true, false),
            new ColumnInfo("status", "character varying", false, true)
        ]
        def tableInfo = new TableInfo("composite_delete_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_delete_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                customer_id: 1,
                order_id: 101
            ]
        }

        when: "deleting record using composite primary key"
        def mutationResolver = mutator.createDeleteMutationResolver("composite_delete_table")
        def result = mutationResolver.get(environment)

        then: "should return deleted record"
        result.customer_id == 1
        result.order_id == 101
        result.status == "pending"

        and: "should be deleted from database"
        def remainingRecords = jdbcTemplate.queryForList("SELECT * FROM test_schema.composite_delete_table")
        remainingRecords.size() == 2
        !remainingRecords.any { it.customer_id == 1 && it.order_id == 101 }

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_delete_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should fail to create duplicate composite key record"() {
        given: "a table with composite primary key and existing data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_duplicate_table (
                part1 INTEGER NOT NULL,
                part2 VARCHAR(50) NOT NULL,
                value TEXT,
                PRIMARY KEY (part1, part2)
            )
        """)

        // Insert existing record
        jdbcTemplate.execute("""
            INSERT INTO test_schema.composite_duplicate_table (part1, part2, value) 
            VALUES (1, 'test', 'original value')
        """)

        def columns = [
            new ColumnInfo("part1", "integer", true, false),
            new ColumnInfo("part2", "character varying", true, false),
            new ColumnInfo("value", "text", false, true)
        ]
        def tableInfo = new TableInfo("composite_duplicate_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_duplicate_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                part1: 1,           // Same as existing
                part2: "test",      // Same as existing
                value: "new value"  // Different value
            ]
        }

        when: "attempting to create duplicate composite key"
        def mutationResolver = mutator.createCreateMutationResolver("composite_duplicate_table")
        mutationResolver.get(environment)

        then: "should throw exception for duplicate key"
        thrown(DataMutationException)

        and: "original record should remain unchanged"
        def dbResults = jdbcTemplate.queryForList("SELECT * FROM test_schema.composite_duplicate_table WHERE part1 = ? AND part2 = ?", 1, "test")
        dbResults.size() == 1
        dbResults[0].value == "original value"

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_duplicate_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should reject incomplete composite key in update mutation"() {
        given: "a table with composite primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_incomplete_table (
                id1 INTEGER NOT NULL,
                id2 INTEGER NOT NULL,
                description TEXT,
                PRIMARY KEY (id1, id2)
            )
        """)

        def columns = [
            new ColumnInfo("id1", "integer", true, false),
            new ColumnInfo("id2", "integer", true, false),
            new ColumnInfo("description", "text", false, true)
        ]
        def tableInfo = new TableInfo("composite_incomplete_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_incomplete_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                id1: 1,                           // Only one part of composite key
                description: "Updated description" // Missing id2
            ]
        }

        when: "attempting to update with incomplete composite key"
        def mutationResolver = mutator.createUpdateMutationResolver("composite_incomplete_table")
        mutationResolver.get(environment)

        then: "should throw exception for missing primary key part"
        thrown(DataMutationException)

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_incomplete_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should handle bulk create operations on composite key tables"() {
        given: "a table with composite primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_bulk_table (
                region_id INTEGER NOT NULL,
                product_code VARCHAR(20) NOT NULL,
                stock_quantity INTEGER,
                last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (region_id, product_code)
            )
        """)

        def columns = [
            new ColumnInfo("region_id", "integer", true, false),
            new ColumnInfo("product_code", "character varying", true, false),
            new ColumnInfo("stock_quantity", "integer", false, true),
            new ColumnInfo("last_updated", "timestamp", false, true)
        ]
        def tableInfo = new TableInfo("composite_bulk_table", columns, [], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_bulk_table": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("inputs") >> [
                [region_id: 1, product_code: "ABC123", stock_quantity: 100],
                [region_id: 1, product_code: "DEF456", stock_quantity: 50],
                [region_id: 2, product_code: "ABC123", stock_quantity: 75],
                [region_id: 2, product_code: "GHI789", stock_quantity: 200]
            ]
        }

        when: "performing bulk create on composite key table"
        def bulkCreateResolver = mutator.createBulkCreateMutationResolver("composite_bulk_table")
        def results = bulkCreateResolver.get(environment)

        then: "should create all records successfully"
        results.size() == 4
        results.every { it.region_id != null && it.product_code != null }
        
        and: "should verify unique composite keys"
        def uniqueKeys = results.collect { "${it.region_id}-${it.product_code}" }.unique()
        uniqueKeys.size() == 4

        and: "should be persisted in database"
        def dbCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_schema.composite_bulk_table", Integer.class)
        dbCount == 4

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_bulk_table")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should handle foreign key violations with composite keys"() {
        given: "parent and child tables with composite foreign keys"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_parent (
                parent_id1 INTEGER NOT NULL,
                parent_id2 INTEGER NOT NULL,
                name VARCHAR(100),
                PRIMARY KEY (parent_id1, parent_id2)
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_child (
                child_id SERIAL PRIMARY KEY,
                parent_id1 INTEGER NOT NULL,
                parent_id2 INTEGER NOT NULL,
                description TEXT,
                FOREIGN KEY (parent_id1, parent_id2) REFERENCES test_schema.composite_parent(parent_id1, parent_id2)
            )
        """)

        // Insert valid parent record
        jdbcTemplate.execute("""
            INSERT INTO test_schema.composite_parent (parent_id1, parent_id2, name) 
            VALUES (1, 1, 'Valid Parent')
        """)

        def columns = [
            new ColumnInfo("child_id", "integer", true, false),
            new ColumnInfo("parent_id1", "integer", false, false),
            new ColumnInfo("parent_id2", "integer", false, false),
            new ColumnInfo("description", "text", false, true)
        ]
        def tableInfo = new TableInfo("composite_child", columns, [
            new ForeignKeyInfo("parent_id1,parent_id2", "composite_parent", "parent_id1,parent_id2")
        ], false)
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["composite_child": tableInfo]

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                parent_id1: 1,
                parent_id2: 999,  // Non-existent parent combination
                description: "Orphaned child"
            ]
        }

        when: "creating child with invalid composite foreign key"
        def mutationResolver = mutator.createCreateMutationResolver("composite_child")
        mutationResolver.get(environment)

        then: "should throw exception for foreign key violation"
        thrown(DataMutationException)

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_child")
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_parent")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should create nested relationships involving composite keys"() {
        given: "complex table structure with composite keys and relationships"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_customers (
                customer_id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.composite_orders (
                customer_id INTEGER NOT NULL,
                order_number INTEGER NOT NULL,
                order_date DATE DEFAULT CURRENT_DATE,
                total_amount DECIMAL(10,2),
                PRIMARY KEY (customer_id, order_number),
                FOREIGN KEY (customer_id) REFERENCES test_schema.composite_customers(customer_id)
            )
        """)

        // Insert existing customer
        jdbcTemplate.execute("""
            INSERT INTO test_schema.composite_customers (customer_id, name) 
            VALUES (1, 'Test Customer')
        """)

        def customerTableInfo = new TableInfo(
            name: "composite_customers",
            columns: [
                new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                new ColumnInfo(name: "name", type: "character varying", primaryKey: false, nullable: false)
            ],
            foreignKeys: [],
            view: false
        )

        def orderTableInfo = new TableInfo(
            name: "composite_orders",
            columns: [
                new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                new ColumnInfo(name: "order_number", type: "integer", primaryKey: true, nullable: false),
                new ColumnInfo(name: "order_date", type: "date", primaryKey: false, nullable: true),
                new ColumnInfo(name: "total_amount", type: "numeric", primaryKey: false, nullable: true)
            ],
            foreignKeys: [
                new ForeignKeyInfo("customer_id", "composite_customers", "customer_id")
            ],
            view: false
        )
        
        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> [
            "composite_customers": customerTableInfo,
            "composite_orders": orderTableInfo
        ]

        and: "transaction template is configured"
        transactionTemplate.execute(_) >> { args ->
            def callback = args[0]
            return callback.doInTransaction(null)
        }

        def environment = Mock(DataFetchingEnvironment) {
            getArgument("input") >> [
                customer_id: 1,
                order_number: 1001,
                total_amount: 299.99,
                composite_customers_connect: [id: 1]
            ]
        }

        when: "creating order with composite key and relationship"
        def createResolver = mutator.createCreateWithRelationshipsMutationResolver("composite_orders")
        def result = createResolver.get(environment)

        then: "should create order with proper composite key and relationship"
        result.customer_id == 1
        result.order_number == 1001
        result.total_amount == 299.99

        and: "should be persisted with correct foreign key"
        def dbResults = jdbcTemplate.queryForList("""
            SELECT o.*, c.name as customer_name 
            FROM test_schema.composite_orders o 
            JOIN test_schema.composite_customers c ON o.customer_id = c.customer_id
            WHERE o.customer_id = ? AND o.order_number = ?
        """, 1, 1001)
        dbResults.size() == 1
        dbResults[0].customer_name == "Test Customer"

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_orders")
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.composite_customers")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}