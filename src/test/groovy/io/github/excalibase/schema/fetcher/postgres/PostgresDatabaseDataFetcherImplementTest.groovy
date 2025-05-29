package io.github.excalibase.schema.fetcher.postgres

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

class PostgresDatabaseDataFetcherImplementTest extends Specification {

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

        // Set up test schema
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS test_schema")
    }

    def cleanup() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS test_schema CASCADE")
    }

    def "should create table data fetcher and fetch all data"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.users (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(255),
                age INTEGER,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.users (name, email, age) VALUES 
            ('John Doe', 'john@example.com', 30),
            ('Jane Smith', 'jane@example.com', 25),
            ('Bob Johnson', 'bob@example.com', 35)
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
        def environment = createMockEnvironment(
                ["id", "name", "email", "age"],
                [:]
        )

        when: "creating and executing table data fetcher"
        def fetcher = dataFetcher.createTableDataFetcher("users")
        def result = fetcher.get(environment)

        then: "should return all users"
        result.size() == 3
        result[0].name == "John Doe"
        result[1].name == "Jane Smith"
        result[2].name == "Bob Johnson"
    }

    def "should handle filtering with various operators"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.products (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                price DECIMAL(10,2),
                description TEXT,
                in_stock BOOLEAN DEFAULT true
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.products (name, price, description, in_stock) VALUES 
            ('Laptop', 999.99, 'High-performance laptop', true),
            ('Mouse', 29.99, 'Wireless mouse', true),
            ('Keyboard', 79.99, 'Mechanical keyboard', false),
            ('Monitor', 299.99, 'Ultra-wide monitor', true)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "products",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "description", type: "text", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "in_stock", type: "boolean", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["products": tableInfo]

        when: "filtering with greater than operator"
        def environment = createMockEnvironment(
                ["id", "name", "price"],
                ["price_gt": 50.0]
        )
        def fetcher = dataFetcher.createTableDataFetcher("products")
        def result = fetcher.get(environment)

        then: "should return products with price > 50"
        result.size() == 3
        result.every { it.price > 50.0 }

        when: "filtering with contains operator"
        environment = createMockEnvironment(
                ["id", "name", "description"],
                ["description_contains": "mouse"]
        )
        result = fetcher.get(environment)

        then: "should return products containing 'mouse' in description"
        result.size() == 1
        result[0].name == "Mouse"

        when: "filtering with boolean value"
        environment = createMockEnvironment(
                ["id", "name", "in_stock"],
                ["in_stock": true]
        )
        result = fetcher.get(environment)

        then: "should return only in-stock products"
        result.size() == 3
        result.every { it.in_stock == true }
    }

    def "should handle ordering and pagination"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.items (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        (1..10).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.items (name) VALUES ('Item ${i}')
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "items",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["items": tableInfo]

        when: "fetching with limit and offset"
        def environment = createMockEnvironment(
                ["id", "name"],
                [
                        "limit": 3,
                        "offset": 2,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createTableDataFetcher("items")
        def result = fetcher.get(environment)

        then: "should return correct page of results"
        result.size() == 3
        result[0].id == 3
        result[1].id == 4
        result[2].id == 5

        when: "fetching with different ordering"
        environment = createMockEnvironment(
                ["id", "name"],
                [
                        "limit": 2,
                        "orderBy": ["id": "DESC"]
                ]
        )
        result = fetcher.get(environment)

        then: "should return results in descending order"
        result.size() == 2
        result[0].id > result[1].id
    }

    def "should create connection data fetcher with cursor pagination"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.posts (
                id SERIAL PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                content TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        (1..5).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.posts (title, content) VALUES ('Post ${i}', 'Content ${i}')
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "posts",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(200)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "content", type: "text", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["posts": tableInfo]

        when: "fetching with first parameter"
        def environment = createMockConnectionEnvironment(
                ["id", "title", "content"],
                [
                        "first": 2,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("posts")
        def result = fetcher.get(environment)

        then: "should return connection with edges and pageInfo"
        result.containsKey("edges")
        result.containsKey("pageInfo")
        result.containsKey("totalCount")

        result.edges.size() == 2
        result.totalCount == 5

        def pageInfo = result.pageInfo
        pageInfo.getAt("hasNextPage") == true
        pageInfo.getAt("hasPreviousPage") == false
        pageInfo.startCursor != null
        pageInfo.endCursor != null
    }

    def "should handle connection fetcher without orderBy for cursor pagination"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.reviews (
                id SERIAL PRIMARY KEY,
                rating INTEGER,
                comment TEXT
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.reviews (rating, comment) VALUES (5, 'Great product!')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "reviews",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "rating", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "comment", type: "text", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["reviews": tableInfo]

        when: "fetching with cursor pagination but no orderBy"
        def environment = createMockConnectionEnvironment(
                ["id", "rating", "comment"],
                ["first": 10] // No orderBy parameter
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("reviews")
        def result = fetcher.get(environment)

        then: "should return error about missing orderBy"
        result.containsKey("error")
        result.error == "orderBy parameter is required for cursor-based pagination"
        result.edges.isEmpty()
        result.totalCount == 0
    }

    def "should create relationship data fetcher"() {
        given: "tables with foreign key relationship"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.categories (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL
            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.articles (
                id SERIAL PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                category_id INTEGER,
                FOREIGN KEY (category_id) REFERENCES test_schema.categories(id)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.categories (name) VALUES ('Tech'), ('Sports')
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.articles (title, category_id) VALUES 
            ('AI News', 1),
            ('Football Results', 2)
        """)

        when: "creating relationship data fetcher"
        def fetcher = dataFetcher.createRelationshipDataFetcher(
                "articles", "category_id", "categories", "id"
        )

        and: "executing with source data"
        def environment = createMockRelationshipEnvironment(
                ["id", "name"],
                ["category_id": 1] // Source object
        )
        def result = fetcher.get(environment)

        then: "should return related record"
        result != null
        result.id == 1
        result.name == "Tech"
    }

    def "should handle relationship data fetcher with null foreign key"() {
        given: "relationship data fetcher"
        def fetcher = dataFetcher.createRelationshipDataFetcher(
                "articles", "category_id", "categories", "id"
        )

        when: "executing with null foreign key"
        def environment = createMockRelationshipEnvironment(
                ["id", "name"],
                ["category_id": null]
        )
        def result = fetcher.get(environment)

        then: "should return null"
        result == null
    }

    def "should handle UUID type filtering"() {
        given: "a table with UUID column"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.sessions (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id INTEGER,
                token VARCHAR(255)
            )
        """)

        def uuid = UUID.randomUUID()
        jdbcTemplate.execute("""
            INSERT INTO test_schema.sessions (id, user_id, token) VALUES 
            ('${uuid}', 123, 'token123')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "sessions",
                columns: [
                        new ColumnInfo(name: "id", type: "uuid", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "user_id", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "token", type: "character varying(255)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["sessions": tableInfo]

        when: "filtering by UUID"
        def environment = createMockEnvironment(
                ["id", "user_id", "token"],
                ["id": uuid.toString()]
        )
        def fetcher = dataFetcher.createTableDataFetcher("sessions")
        def result = fetcher.get(environment)

        then: "should return matching session"
        result.size() == 1
        result[0].id == uuid
        result[0].user_id == 123
    }

    // Helper methods to create mock environments
    private DataFetchingEnvironment createMockEnvironment(List<String> selectedFields, Map<String, Object> arguments) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)
        def fields = selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        }

        selectionSet.getFields() >> fields
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> arguments
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }

    private DataFetchingEnvironment createMockConnectionEnvironment(List<String> selectedFields, Map<String, Object> arguments) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)

        // Mock the nested structure for connection queries (edges.node.fields)
        def nodeFields = selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        }

        def nodeSelectionSet = Mock(DataFetchingFieldSelectionSet)
        nodeSelectionSet.getFields() >> nodeFields

        def nodeField = Mock(SelectedField)
        nodeField.getName() >> "node"
        nodeField.getSelectionSet() >> nodeSelectionSet

        def edgeSelectionSet = Mock(DataFetchingFieldSelectionSet)
        edgeSelectionSet.getFields() >> [nodeField]

        def edgesField = Mock(SelectedField)
        edgesField.getName() >> "edges"
        edgesField.getSelectionSet() >> edgeSelectionSet

        def pageInfoField = Mock(SelectedField)
        pageInfoField.getName() >> "pageInfo"

        def totalCountField = Mock(SelectedField)
        totalCountField.getName() >> "totalCount"

        selectionSet.getFields() >> [edgesField, pageInfoField, totalCountField]
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> arguments
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }

    private DataFetchingEnvironment createMockRelationshipEnvironment(List<String> selectedFields, Map<String, Object> source) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)
        def fields = selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        }

        selectionSet.getFields() >> fields
        environment.getSelectionSet() >> selectionSet
        environment.getSource() >> source
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }
}