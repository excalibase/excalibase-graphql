package io.github.excalibase.schema.fetcher.postgres

import graphql.GraphQLContext
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingFieldSelectionSet
import graphql.schema.SelectedField
import io.github.excalibase.config.AppConfig
import io.github.excalibase.constant.DatabaseType
import io.github.excalibase.constant.SupportedDatabaseConstant
import io.github.excalibase.exception.DataFetcherException
import io.github.excalibase.model.ColumnInfo
import io.github.excalibase.model.ForeignKeyInfo
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
        pageInfo.hasNextPage == true
        pageInfo.hasPreviousPage == false
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
                ["first": 10]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("reviews")
        def result = fetcher.get(environment)

        then: "should return cursor and start/end cursor have warning message"
        def edges = result.get("edges") as HashMap
        edges.get("cursor") == "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
        edges.get("node").get("id") == 1
        edges.get("node").get("rating") == 5
        edges.get("node").get("comment") == "Great product!"
        def pageInfo = result.get("pageInfo") as HashMap
        pageInfo.get("hasNextPage") == false
        pageInfo.get("hasPreviousPage") == false
        pageInfo.get("startCursor") == "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
        pageInfo.get("endCursor") == "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
        result.totalCount == 1
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

        and: "mocked schema reflector for categories table"
        def tableInfo = new TableInfo(
                name: "categories",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["categories": tableInfo]

        when: "creating relationship data fetcher"
        def fetcher = dataFetcher.createRelationshipDataFetcher(
                "articles", "category_id", "categories", "id"
        )

        and: "executing with source data"
        def environment = createMockRelationshipEnvironment(
                ["id", "name"],
                ["category_id": 1]
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

    def "should handle various string filtering operators"() {
        given: "a table with test data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.articles (
                id SERIAL PRIMARY KEY,
                title VARCHAR(200) NOT NULL,
                content TEXT,
                author VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.articles (title, content, author) VALUES 
            ('JavaScript Basics', 'Learn the fundamentals of JavaScript programming', 'John Smith'),
            ('Advanced Python', 'Deep dive into Python frameworks', 'Jane Doe'),
            ('Web Development', 'Complete guide to modern web development', 'Bob Wilson'),
            ('Data Science with R', 'Statistical analysis using R language', 'Alice Johnson')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "articles",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(200)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "content", type: "text", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "author", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["articles": tableInfo]

        when: "filtering with startsWith operator"
        def environment = createMockEnvironment(
                ["id", "title", "author"],
                ["title_startsWith": "Web"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("articles")
        def result = fetcher.get(environment)

        then: "should return articles starting with 'Web'"
        result.size() == 1
        result[0].title == "Web Development"

        when: "filtering with endsWith operator"
        environment = createMockEnvironment(
                ["id", "title", "author"],
                ["title_endsWith": "Python"]
        )
        result = fetcher.get(environment)

        then: "should return articles ending with 'Python'"
        result.size() == 1
        result[0].title == "Advanced Python"

        when: "filtering with contains operator"
        environment = createMockEnvironment(
                ["id", "title", "content"],
                ["content_contains": "frameworks"]
        )
        result = fetcher.get(environment)

        then: "should return articles containing 'frameworks'"
        result.size() == 1
        result[0].title == "Advanced Python"
    }

    def "should handle null filtering operators"() {
        given: "a table with some null data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.profiles (
                id SERIAL PRIMARY KEY,
                username VARCHAR(100) NOT NULL,
                email VARCHAR(255),
                phone VARCHAR(20)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.profiles (username, email, phone) VALUES 
            ('user1', 'user1@example.com', '123-456-7890'),
            ('user2', 'user2@example.com', NULL),
            ('user3', NULL, '987-654-3210'),
            ('user4', NULL, NULL)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "profiles",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "username", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "character varying(255)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "phone", type: "character varying(20)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["profiles": tableInfo]

        when: "filtering with isNull operator"
        def environment = createMockEnvironment(
                ["id", "username", "email"],
                ["email_isNull": true]
        )
        def fetcher = dataFetcher.createTableDataFetcher("profiles")
        def result = fetcher.get(environment)

        then: "should return profiles with null email"
        result.size() == 2
        result.every { it.email == null }

        when: "filtering with isNotNull operator"
        environment = createMockEnvironment(
                ["id", "username", "phone"],
                ["phone_isNotNull": true]
        )
        result = fetcher.get(environment)

        then: "should return profiles with non-null phone"
        result.size() == 2
        result.every { it.phone != null }
    }

    def "should handle numeric comparison operators"() {
        given: "a table with numeric data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.scores (
                id SERIAL PRIMARY KEY,
                player_name VARCHAR(100) NOT NULL,
                score INTEGER,
                rating DECIMAL(5,2),
                level BIGINT
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.scores (player_name, score, rating, level) VALUES 
            ('Alice', 85, 4.5, 100),
            ('Bob', 92, 4.8, 150),
            ('Charlie', 78, 3.9, 75),
            ('David', 95, 4.9, 200)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "scores",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "player_name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "score", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "rating", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "level", type: "bigint", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["scores": tableInfo]

        when: "filtering with gte operator"
        def environment = createMockEnvironment(
                ["id", "player_name", "score"],
                ["score_gte": 90]
        )
        def fetcher = dataFetcher.createTableDataFetcher("scores")
        def result = fetcher.get(environment)

        then: "should return scores >= 90"
        result.size() == 2
        result.every { it.score >= 90 }

        when: "filtering with lt operator"
        environment = createMockEnvironment(
                ["id", "player_name", "score"],
                ["score_lt": 80]
        )
        result = fetcher.get(environment)

        then: "should return scores < 80"
        result.size() == 1
        result[0].player_name == "Charlie"

        when: "filtering with lte operator on decimal"
        environment = createMockEnvironment(
                ["id", "player_name", "rating"],
                ["rating_lte": 4.0]
        )
        result = fetcher.get(environment)

        then: "should return ratings <= 4.0"
        result.size() == 1
        result[0].player_name == "Charlie"
    }

    def "should handle cursor pagination with after parameter"() {
        given: "a table with ordered data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.events (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                event_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        (1..5).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.events (name) VALUES ('Event ${i}')
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "events",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "event_date", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["events": tableInfo]

        when: "getting first page to obtain cursor"
        def environment = createMockConnectionEnvironment(
                ["id", "name"],
                [
                        "first": 2,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("events")
        def firstPageResult = fetcher.get(environment)
        def endCursor = firstPageResult.pageInfo.endCursor

        and: "using after cursor for next page"
        environment = createMockConnectionEnvironment(
                ["id", "name"],
                [
                        "first": 2,
                        "after": endCursor,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def secondPageResult = fetcher.get(environment)

        then: "should return next page of results"
        secondPageResult.edges.size() == 2
        def nodes = secondPageResult.edges.collect { it.node }
        nodes[0].id == 3
        nodes[1].id == 4
        secondPageResult.pageInfo.hasNextPage == true
        secondPageResult.pageInfo.hasPreviousPage == true
    }

    def "should handle cursor pagination with before parameter"() {
        given: "a table with ordered data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.tasks (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100) NOT NULL,
                priority INTEGER DEFAULT 1
            )
        """)

        (1..5).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.tasks (title, priority) VALUES ('Task ${i}', ${i})
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "tasks",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "priority", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["tasks": tableInfo]

        when: "getting a middle page to obtain cursor"
        def environment = createMockConnectionEnvironment(
                ["id", "title"],
                [
                        "first": 2,
                        "after": Base64.getEncoder().encodeToString("id:2".getBytes()),
                        "orderBy": ["id": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("tasks")
        def middlePageResult = fetcher.get(environment)
        def startCursor = middlePageResult.pageInfo.startCursor

        and: "using before cursor for previous page"
        environment = createMockConnectionEnvironment(
                ["id", "title"],
                [
                        "last": 2,
                        "before": startCursor,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def previousPageResult = fetcher.get(environment)

        then: "should return previous page of results"
        previousPageResult.edges.size() <= 2
        previousPageResult.pageInfo.hasPreviousPage == false
    }

    def "should handle malformed cursor gracefully"() {
        given: "a table with data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.logs (
                id SERIAL PRIMARY KEY,
                message TEXT
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.logs (message) VALUES ('Log entry 1')
        """)

        and: "mocked schema reflector"
        def environment = createMockConnectionEnvironment(
                ["id", "message"],
                [
                        "first": 2,
                        "after": "invalid-cursor",
                        "orderBy": ["id": "ASC"]
                ]
        )
        def tableInfo = new TableInfo(
                name: "logs",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "message", type: "text", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["logs": tableInfo]

        when: "using malformed after cursor"
        def fetcher = dataFetcher.createConnectionDataFetcher("logs")
        def result = fetcher.get(environment)
        then: "should throw DataFetcherException"
        def e = thrown(DataFetcherException)
        e.getMessage() == "Invalid cursor format for 'after': invalid-cursor"
    }

    def "should handle relationship fetcher with batch context"() {
        given: "relationship setup and batch context"
        def batchContext = [
                "authors": [
                        1: [id: 1, name: "Stephen King", email: "stephen@example.com"],
                        2: [id: 2, name: "J.K. Rowling", email: "jk@example.com"]
                ]
        ]

        when: "creating relationship data fetcher"
        def fetcher = dataFetcher.createRelationshipDataFetcher(
                "books", "author_id", "authors", "id"
        )

        and: "executing with batch context available"
        def environment = createMockRelationshipEnvironmentWithBatch(
                ["id", "name", "email"],
                ["author_id": 1],
                batchContext
        )
        def result = fetcher.get(environment)

        then: "should return related record from batch context"
        result != null
        result.id == 1
        result.name == "Stephen King"
    }

    def "should handle different data types in filtering"() {
        given: "a table with various data types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.mixed_types (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                count INTEGER,
                amount BIGINT,
                price DECIMAL(10,2),
                rate DOUBLE PRECISION,
                is_active BOOLEAN,
                name VARCHAR(100)
            )
        """)

        def uuid1 = UUID.randomUUID()
        def uuid2 = UUID.randomUUID()

        jdbcTemplate.execute("""
            INSERT INTO test_schema.mixed_types (id, count, amount, price, rate, is_active, name) VALUES 
            ('${uuid1}', 10, 1000000, 99.99, 4.75, true, 'Item A'),
            ('${uuid2}', 20, 2000000, 199.99, 3.25, false, 'Item B')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "mixed_types",
                columns: [
                        new ColumnInfo(name: "id", type: "uuid", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "count", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "amount", type: "bigint", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "price", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "rate", type: "double precision", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "is_active", type: "boolean", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["mixed_types": tableInfo]

        when: "filtering with string representation of integer"
        def environment = createMockEnvironment(
                ["id", "count", "name"],
                ["count": "10"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("mixed_types")
        def result = fetcher.get(environment)

        then: "should handle type conversion correctly"
        result.size() == 1
        result[0].count == 10

        when: "filtering with string representation of bigint"
        environment = createMockEnvironment(
                ["id", "amount", "name"],
                ["amount": "2000000"]
        )
        result = fetcher.get(environment)

        then: "should handle bigint conversion correctly"
        result.size() == 1
        result[0].amount == 2000000L

        when: "filtering with boolean"
        environment = createMockEnvironment(
                ["id", "is_active", "name"],
                ["is_active": false]
        )
        result = fetcher.get(environment)

        then: "should handle boolean filtering correctly"
        result.size() == 1
        result[0].is_active == false
    }

    def "should handle multi-field cursor pagination"() {
        given: "a table with duplicate values that requires multi-field ordering"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.rankings (
                id SERIAL PRIMARY KEY,
                score INTEGER,
                name VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.rankings (score, name) VALUES 
            (100, 'Alice'),
            (100, 'Bob'),
            (95, 'Charlie'),
            (95, 'David'),
            (90, 'Eve')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "rankings",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "score", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["rankings": tableInfo]

        when: "fetching with multi-field ordering"
        def environment = createMockConnectionEnvironment(
                ["id", "score", "name"],
                [
                        "first": 2,
                        "orderBy": ["score": "DESC", "name": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("rankings")
        def result = fetcher.get(environment)

        then: "should handle complex cursor pagination correctly"
        result.edges.size() == 2
        def nodes = result.edges.collect { it.node }
        nodes[0].name == "Alice"
        nodes[1].name == "Bob"

        // Verify cursors are generated for multi-field ordering
        result.edges.every { it.cursor != null }
        result.pageInfo.startCursor != null
        result.pageInfo.endCursor != null
    }

    def "should handle invalid UUID format gracefully"() {
        given: "a table with UUID column"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.tokens (
                id UUID PRIMARY KEY,
                value VARCHAR(255)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "tokens",
                columns: [
                        new ColumnInfo(name: "id", type: "uuid", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "value", type: "character varying(255)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["tokens": tableInfo]

        when: "filtering with invalid UUID format"
        def environment = createMockEnvironment(
                ["id", "value"],
                ["id": "not-a-valid-uuid"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("tokens")
        fetcher.get(environment)

        then: "should throw DataFetcherException for invalid UUID"
        thrown(Exception)
    }

    def "should handle empty table gracefully"() {
        given: "an empty table"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.empty_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "empty_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["empty_table": tableInfo]

        when: "fetching data from empty table"
        def environment = createMockEnvironment(["id", "name"], [:])
        def fetcher = dataFetcher.createTableDataFetcher("empty_table")
        def result = fetcher.get(environment)

        then: "should return empty list"
        result.size() == 0
        result instanceof List
    }

    def "should handle connection fetcher with empty results"() {
        given: "an empty table"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.empty_connections (
                id SERIAL PRIMARY KEY,
                value VARCHAR(100)
            )
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "empty_connections",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "value", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["empty_connections": tableInfo]

        when: "fetching connection from empty table"
        def environment = createMockConnectionEnvironment(
                ["id", "value"],
                ["first": 10, "orderBy": ["id": "ASC"]]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("empty_connections")
        def result = fetcher.get(environment)

        then: "should return empty connection structure"
        result.edges.size() == 0
        result.totalCount == 0
        result.pageInfo.hasNextPage == false
        result.pageInfo.hasPreviousPage == false
    }

    def "should handle offset-based pagination in connection fetcher"() {
        given: "a table with data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.offset_test (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100)
            )
        """)

        (1..10).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.offset_test (name) VALUES ('Item ${i}')
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "offset_test",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["offset_test": tableInfo]

        when: "using offset-based pagination"
        def environment = createMockConnectionEnvironment(
                ["id", "name"],
                ["offset": 3, "first": 2]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("offset_test")
        def result = fetcher.get(environment)

        then: "should return correct offset-based results"
        result.edges.size() == 2
        result.totalCount == 10
        def nodes = result.edges.collect { it.node }
        nodes[0].id == 4
        nodes[1].id == 5
    }

    def "should handle serial type columns"() {
        given: "a table with serial columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.serial_test (
                id SERIAL PRIMARY KEY,
                sequence_num SERIAL,
                name VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.serial_test (name) VALUES ('Test Item')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "serial_test",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "sequence_num", type: "serial", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["serial_test": tableInfo]

        when: "filtering with serial column as string"
        def environment = createMockEnvironment(
                ["id", "sequence_num", "name"],
                ["sequence_num": "1"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("serial_test")
        def result = fetcher.get(environment)

        then: "should handle serial type correctly"
        result.size() == 1
        result[0].sequence_num == 1
    }

    def "should handle complex filtering combinations"() {
        given: "a table with mixed data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.complex_filter (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                age INTEGER,
                salary DECIMAL(10,2),
                is_active BOOLEAN
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.complex_filter (name, age, salary, is_active) VALUES 
            ('Alice', 30, 50000.00, true),
            ('Bob', 25, 45000.00, true),
            ('Charlie', 35, 60000.00, false),
            ('David', 28, 55000.00, true)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "complex_filter",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "age", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "salary", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "is_active", type: "boolean", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["complex_filter": tableInfo]

        when: "applying multiple filters"
        def environment = createMockEnvironment(
                ["id", "name", "age", "salary"],
                [
                        "age_gte": 28,
                        "salary_gt": 50000.00,
                        "is_active": true,
                        "name_contains": "a"
                ]
        )
        def fetcher = dataFetcher.createTableDataFetcher("complex_filter")
        def result = fetcher.get(environment)

        then: "should apply all filters correctly"
        result.size() == 1
        result[0].name == "David"
    }

    def "should handle null values in cursor fields"() {
        given: "a table with potential null values in ordering fields"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.nullable_order (
                id SERIAL PRIMARY KEY,
                priority INTEGER,
                name VARCHAR(100)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.nullable_order (priority, name) VALUES 
            (1, 'High Priority'),
            (NULL, 'No Priority'),
            (2, 'Medium Priority')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "nullable_order",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "priority", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["nullable_order": tableInfo]

        when: "using cursor pagination with nullable order field"
        def environment = createMockConnectionEnvironment(
                ["id", "priority", "name"],
                [
                        "first": 2,
                        "orderBy": ["priority": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("nullable_order")
        def result = fetcher.get(environment)

        then: "should handle null values in cursors gracefully"
        result.edges.size() == 2
        result.edges.every { it.cursor != null }
    }

    def "should handle table without primary key"() {
        given: "a table without primary key"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.no_pk_table (
                name VARCHAR(100),
                value INTEGER
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.no_pk_table (name, value) VALUES ('test', 123)
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "no_pk_table",
                columns: [
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "value", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["no_pk_table": tableInfo]

        when: "fetching from table without primary key"
        def environment = createMockEnvironment(["name", "value"], [:])
        def fetcher = dataFetcher.createTableDataFetcher("no_pk_table")
        def result = fetcher.get(environment)

        then: "should work correctly"
        result.size() == 1
        result[0].name == "test"
        result[0].value == 123
    }

    def "should handle relationship with missing foreign key info"() {
        given: "tables without proper foreign key relationship defined"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.orphan_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                parent_id INTEGER
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.orphan_table (name, parent_id) VALUES ('Orphan', 999)
        """)

        and: "mocked schema reflector with no foreign key info"
        def tableInfo = new TableInfo(
                name: "orphan_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "parent_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["orphan_table": tableInfo]

        when: "trying to preload relationships that don't exist"
        def environment = createMockEnvironmentWithRelationships(
                ["id", "name", "parent_id"],
                ["nonexistent_relationship"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("orphan_table")
        def result = fetcher.get(environment)

        then: "should handle gracefully without errors"
        result.size() == 1
        result[0].name == "Orphan"
    }

    def "should handle preloadRelationships with empty foreign key values"() {
        given: "tables with relationships but null foreign key values"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.comments (
            id SERIAL PRIMARY KEY,
            text VARCHAR(500),
            post_id INTEGER
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.comments (text, post_id) VALUES 
        ('Comment 1', NULL),
        ('Comment 2', NULL)
    """)

        and: "mocked schema reflector"
        def commentsTableInfo = new TableInfo(
                name: "comments",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "text", type: "character varying(500)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "post_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "post_id", referencedTable: "posts", referencedColumn: "id")
                ]
        )

        def postsTableInfo = new TableInfo(
                name: "posts",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(200)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )

        schemaReflector.reflectSchema() >> ["comments": commentsTableInfo, "posts": postsTableInfo]

        when: "fetching comments with relationship fields but all foreign keys are null"
        def environment = createMockEnvironmentWithRelationships(
                ["id", "text", "post_id"],
                ["posts"], // relationship field
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("comments")
        def result = fetcher.get(environment)

        then: "should handle gracefully without attempting to query related records"
        result.size() == 2
        result.every { it.text != null }

        // Batch context should be created but posts should not be queried due to empty foreign key values
        def batchContext = environment.getGraphQlContext().get("BATCH_CONTEXT")
        batchContext != null
    }

    def "should handle preloadRelationships with null referenced table info"() {
        given: "tables with foreign key pointing to non-existent table"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.orders (
            id SERIAL PRIMARY KEY,
            amount DECIMAL(10,2),
            customer_id INTEGER
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.orders (amount, customer_id) VALUES (100.00, 1)
    """)

        and: "mocked schema reflector with missing referenced table info"
        def ordersTableInfo = new TableInfo(
                name: "orders",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "amount", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "customer_id", referencedTable: "customers", referencedColumn: "id")
                ]
        )

        // Note: customers table info is NOT provided in the schema reflector response
        schemaReflector.reflectSchema() >> ["orders": ordersTableInfo]

        when: "fetching orders with relationship fields but referenced table info is missing"
        def environment = createMockEnvironmentWithRelationships(
                ["id", "amount", "customer_id"],
                ["customers"], // relationship field
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("orders")
        def result = fetcher.get(environment)

        then: "should handle gracefully when referenced table info is null"
        result.size() == 1
        result[0].amount == 100.00
    }

    def "should handle preloadRelationships with empty requested fields"() {
        given: "tables with relationships"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.invoices (
            id SERIAL PRIMARY KEY,
            total DECIMAL(10,2),
            client_id INTEGER
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.invoices (total, client_id) VALUES (250.00, 1)
    """)

        and: "mocked schema reflector"
        def invoicesTableInfo = new TableInfo(
                name: "invoices",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "total", type: "decimal", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "client_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "client_id", referencedTable: "clients", referencedColumn: "id")
                ]
        )

        def clientsTableInfo = new TableInfo(
                name: "clients",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )

        schemaReflector.reflectSchema() >> ["invoices": invoicesTableInfo, "clients": clientsTableInfo]

        when: "fetching with relationship field that has no actual column selections"
        def environment = createMockEnvironmentWithEmptyRelationshipFields(
                ["id", "total", "client_id"],
                ["clients"], // relationship field but with no sub-fields
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("invoices")
        def result = fetcher.get(environment)

        then: "should handle gracefully when no fields are requested for the relationship"
        result.size() == 1
        result[0].total == 250.00
    }

    def "should handle preloadRelationships with relationship field not matching foreign key"() {
        given: "tables with foreign key relationship"
        jdbcTemplate.execute("""
        CREATE TABLE test_schema.tickets (
            id SERIAL PRIMARY KEY,
            subject VARCHAR(200),
            assignee_id INTEGER
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.tickets (subject, assignee_id) VALUES ('Bug Report', 1)
    """)

        and: "mocked schema reflector"
        def ticketsTableInfo = new TableInfo(
                name: "tickets",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "subject", type: "character varying(200)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "assignee_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "assignee_id", referencedTable: "users", referencedColumn: "id")
                ]
        )

        schemaReflector.reflectSchema() >> ["tickets": ticketsTableInfo]

        when: "fetching with relationship field that doesn't match any foreign key referenced table"
        def environment = createMockEnvironmentWithRelationships(
                ["id", "subject", "assignee_id"],
                ["departments"], // This doesn't match any foreign key referenced table
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("tickets")
        def result = fetcher.get(environment)

        then: "should handle gracefully when relationship field doesn't match foreign keys"
        result.size() == 1
        result[0].subject == "Bug Report"
    }

    def "should handle preloadRelationships creating new batch context"() {
        given: "tables with foreign key relationships"
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
            department_id INTEGER,
            FOREIGN KEY (department_id) REFERENCES test_schema.departments(id)
        )
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.departments (name) VALUES ('Engineering'), ('Marketing')
    """)

        jdbcTemplate.execute("""
        INSERT INTO test_schema.employees (name, department_id) VALUES 
        ('John Doe', 1),
        ('Jane Smith', 2),
        ('Bob Johnson', 1)
    """)

        and: "mocked schema reflector"
        def employeesTableInfo = new TableInfo(
                name: "employees",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "department_id", type: "integer", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo(columnName: "department_id", referencedTable: "departments", referencedColumn: "id")
                ]
        )

        def departmentsTableInfo = new TableInfo(
                name: "departments",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: false)
                ],
                foreignKeys: []
        )

        schemaReflector.reflectSchema() >> ["employees": employeesTableInfo, "departments": departmentsTableInfo]

        when: "fetching employees with department relationships (no existing batch context)"
        def environment = createMockEnvironmentWithRelationshipsAndFields(
                ["id", "name", "department_id"],
                ["departments"], // relationship field
                ["id", "name"], // fields requested for the relationship
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("employees")
        def result = fetcher.get(environment)

        then: "should create new batch context and populate it correctly"
        result.size() == 3
        result.every { it.name != null }

        // Verify that batch context was created and populated
        def batchContext = environment.getGraphQlContext().get("BATCH_CONTEXT")
        batchContext != null
        batchContext.containsKey("departments")

        def departmentMap = batchContext.get("departments") as Map
        departmentMap.size() == 2
        departmentMap[1].name == "Engineering"
        departmentMap[2].name == "Marketing"
    }

// Helper method for empty relationship fields
    private DataFetchingEnvironment createMockEnvironmentWithEmptyRelationshipFields(List<String> selectedFields, List<String> relationshipFields, Map<String, Object> arguments) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)

        def allFields = []
        allFields.addAll(selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        })
        allFields.addAll(relationshipFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            def relationshipSelectionSet = Mock(DataFetchingFieldSelectionSet)
            relationshipSelectionSet.getFields() >> [] // Empty fields list
            field.getSelectionSet() >> relationshipSelectionSet
            field
        })

        selectionSet.getFields() >> allFields
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> arguments
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }

    private DataFetchingEnvironment createMockEnvironmentWithRelationshipsAndFields(List<String> selectedFields, List<String> relationshipFields, List<String> relationshipRequestedFields, Map<String, Object> arguments) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)

        def allFields = []
        allFields.addAll(selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        })
        allFields.addAll(relationshipFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            def relationshipSelectionSet = Mock(DataFetchingFieldSelectionSet)
            def relationshipSubFields = relationshipRequestedFields.collect { subFieldName ->
                def subField = Mock(SelectedField)
                subField.getName() >> subFieldName
                subField
            }
            relationshipSelectionSet.getFields() >> relationshipSubFields
            field.getSelectionSet() >> relationshipSelectionSet
            field
        })

        selectionSet.getFields() >> allFields
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> arguments
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        return environment
    }

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

    private DataFetchingEnvironment createMockEnvironmentWithRelationships(List<String> selectedFields, List<String> relationshipFields, Map<String, Object> arguments) {
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)

        def allFields = []
        allFields.addAll(selectedFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            field
        })
        allFields.addAll(relationshipFields.collect { fieldName ->
            def field = Mock(SelectedField)
            field.getName() >> fieldName
            def relationshipSelectionSet = Mock(DataFetchingFieldSelectionSet)
            def relationshipSubFields = [Mock(SelectedField)]
            relationshipSelectionSet.getFields() >> relationshipSubFields
            field.getSelectionSet() >> relationshipSelectionSet
            field
        })

        selectionSet.getFields() >> allFields
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

    private DataFetchingEnvironment createMockRelationshipEnvironmentWithBatch(List<String> selectedFields, Map<String, Object> source, Map<String, Object> batchContext) {
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

        def graphQLContext = GraphQLContext.newContext().of("BATCH_CONTEXT", batchContext).build()
        environment.getGraphQlContext() >> graphQLContext

        return environment
    }
}