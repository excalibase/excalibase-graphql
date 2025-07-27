package io.github.excalibase.postgres.fetcher

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
import io.github.excalibase.postgres.fetcher.PostgresDatabaseDataFetcherImplement
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

        then: "should return cursor and start/end cursor with valid cursor values"
        def edges = result.get("edges") as List
        edges.size() == 1
        def firstEdge = edges[0] as HashMap
        firstEdge.get("cursor") != null
        firstEdge.get("cursor") != "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
        firstEdge.get("node").get("id") == 1
        firstEdge.get("node").get("rating") == 5
        firstEdge.get("node").get("comment") == "Great product!"
        def pageInfo = result.get("pageInfo") as HashMap
        pageInfo.get("hasNextPage") == false
        pageInfo.get("hasPreviousPage") == false
        pageInfo.get("startCursor") != null
        pageInfo.get("startCursor") != "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
        pageInfo.get("endCursor") != null
        pageInfo.get("endCursor") != "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
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
        fetcher.get(environment)
        
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

    // ========== Enhanced PostgreSQL Types Tests ==========

    def "should handle JSON and JSONB type filtering"() {
        given: "a table with JSON and JSONB columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.json_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                json_data JSON,
                jsonb_data JSONB
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.json_table (name, json_data, jsonb_data) VALUES 
            ('Record 1', '{"name": "John", "age": 30}', '{"tags": ["developer", "java"], "active": true}'),
            ('Record 2', '{"name": "Jane", "age": 25}', '{"tags": ["designer", "css"], "active": false}'),
            ('Record 3', NULL, '{"tags": [], "active": true}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "json_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "json_data", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "jsonb_data", type: "jsonb", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["json_table": tableInfo]

        when: "filtering by JSON contains operation"
        def environment = createMockEnvironment(
                ["id", "name", "json_data"],
                ["json_data_contains": "John"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("json_table")
        def result = fetcher.get(environment)

        then: "should return records with JSON containing 'John'"
        result.size() == 1
        result[0].name == "Record 1"

        when: "filtering by JSONB contains operation"
        environment = createMockEnvironment(
                ["id", "name", "jsonb_data"],
                ["jsonb_data_contains": "developer"]
        )
        result = fetcher.get(environment)

        then: "should return records with JSONB containing 'developer'"
        result.size() == 1
        result[0].name == "Record 1"

        when: "filtering for null JSON values"
        environment = createMockEnvironment(
                ["id", "name", "json_data"],
                ["json_data_isNull": true]
        )
        result = fetcher.get(environment)

        then: "should return records with null JSON"
        result.size() == 1
        result[0].name == "Record 3"
    }

    def "should handle interval type filtering with proper casting"() {
        given: "a table with interval columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.interval_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                duration INTERVAL,
                wait_time INTERVAL
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.interval_table (name, duration, wait_time) VALUES 
            ('Task 1', '2 days 3 hours', '30 minutes'),
            ('Task 2', '1 week 2 days', '1 hour'),
            ('Task 3', '30 minutes', '5 minutes')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "interval_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "duration", type: "interval", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "wait_time", type: "interval", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["interval_table": tableInfo]

        when: "filtering by interval exact match"
        def environment = createMockEnvironment(
                ["id", "name", "duration"],
                ["duration": "2 days 3 hours"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("interval_table")
        def result = fetcher.get(environment)

        then: "should return record with matching interval"
        result.size() == 1
        result[0].name == "Task 1"

        when: "filtering by interval greater than"
        environment = createMockEnvironment(
                ["id", "name", "duration"],
                ["duration_gt": "1 hour"]
        )
        result = fetcher.get(environment)

        then: "should return records with duration greater than 1 hour"
        result.size() == 2
        result.every { it.name in ["Task 1", "Task 2"] }

        when: "filtering by interval less than"
        environment = createMockEnvironment(
                ["id", "name", "duration"],
                ["duration_lt": "1 day"]
        )
        result = fetcher.get(environment)

        then: "should return records with duration less than 1 day"
        result.size() == 1
        result[0].name == "Task 3"
    }

    def "should handle network type filtering"() {
        given: "a table with network columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.network_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                ip_address INET,
                subnet CIDR,
                mac_address MACADDR
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.network_table (name, ip_address, subnet, mac_address) VALUES 
            ('Server 1', '192.168.1.1', '192.168.0.0/24', '08:00:27:00:00:00'),
            ('Server 2', '10.0.0.1', '10.0.0.0/16', '00:1B:44:11:3A:B7'),
            ('Server 3', '2001:db8::1', '2001:db8::/32', 'AA:BB:CC:DD:EE:FF')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "network_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "ip_address", type: "inet", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "subnet", type: "cidr", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "mac_address", type: "macaddr", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["network_table": tableInfo]

        when: "filtering by IP address"
        def environment = createMockEnvironment(
                ["id", "name", "ip_address"],
                ["ip_address": "192.168.1.1"]
        )
        def fetcher = dataFetcher.createTableDataFetcher("network_table")
        def result = fetcher.get(environment)

        then: "should return record with matching IP"
        result.size() == 1
        result[0].name == "Server 1"

        when: "filtering by subnet contains"
        environment = createMockEnvironment(
                ["id", "name", "subnet"],
                ["subnet_startsWith": "192.168"]
        )
        result = fetcher.get(environment)

        then: "should return records with subnet starting with '192.168'"
        result.size() == 1
        result[0].name == "Server 1"

        when: "filtering by MAC address pattern"
        environment = createMockEnvironment(
                ["id", "name", "mac_address"],
                ["mac_address_contains": "00:1B"]
        )
        result = fetcher.get(environment)

        then: "should return records with MAC containing '00:1B'"
        result.size() == 1
        result[0].name == "Server 2"
    }

    def "should handle enhanced datetime types"() {
        given: "a table with enhanced datetime columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.datetime_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                event_time TIMESTAMPTZ,
                local_time TIMETZ
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.datetime_table (name, event_time, local_time) VALUES 
            ('Event 1', '2023-01-15 10:30:00+00', '14:30:00+00'),
            ('Event 2', '2023-02-20 15:45:00+00', '09:15:00+00'),
            ('Event 3', '2023-03-25 20:00:00+00', '18:00:00+00')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "datetime_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "event_time", type: "timestamptz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "local_time", type: "timetz", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["datetime_table": tableInfo]

        when: "filtering by timestamptz range"
        def environment = createMockEnvironment(
                ["id", "name", "event_time"],
                [
                        "event_time_gte": "2023-01-01T00:00:00Z",
                        "event_time_lt": "2023-02-01T00:00:00Z"
                ]
        )
        def fetcher = dataFetcher.createTableDataFetcher("datetime_table")
        def result = fetcher.get(environment)

        then: "should return events in the date range"
        result.size() == 1
        result[0].name == "Event 1"

        when: "filtering by timetz exact match"
        environment = createMockEnvironment(
                ["id", "name", "local_time"],
                ["local_time": "14:30:00+00"]
        )
        result = fetcher.get(environment)

        then: "should return events with matching local time"
        result.size() == 1
        result[0].name == "Event 1"
    }

    def "should handle numeric types with precision"() {
        given: "a table with numeric precision columns"

        jdbcTemplate.execute("SET lc_monetary = 'C'")
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.numeric_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                price NUMERIC(10,2),
                cost NUMERIC(10,2)
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.numeric_table (name, price, cost) VALUES 
            ('Product 1', 1234.56, '999.99'),
            ('Product 2', 2500.75, '1500.00'),
            ('Product 3', 0.00, '0.00')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "numeric_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "cost", type: "numeric", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["numeric_table": tableInfo]

        when: "filtering by numeric range"
        def environment = createMockEnvironment(
                ["id", "name", "price"],
                [
                        "price_gte": 1000.00,
                        "price_lte": 2000.00
                ]
        )
        def fetcher = dataFetcher.createTableDataFetcher("numeric_table")
        def result = fetcher.get(environment)

        then: "should return products in price range"
        result.size() == 1
        result[0].name == "Product 1"

        when: "filtering by numeric greater than"
        environment = createMockEnvironment(
                ["id", "name", "cost"],
                ["cost_gt": 1000.00]
        )
        result = fetcher.get(environment)

        then: "should return products with cost greater than 1000"
        result.size() == 1
        result[0].name == "Product 2"
    }

    def "should handle binary and XML types"() {
        given: "a table with binary and XML columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.binary_xml_table (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                binary_data BYTEA,
                xml_data XML
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.binary_xml_table (name, binary_data, xml_data) VALUES 
            ('Record 1', '\\x48656c6c6f', '<person><name>John</name><age>30</age></person>'),
            ('Record 2', '\\x576f726c64', '<product><name>Laptop</name><price>1500</price></product>'),
            ('Record 3', NULL, '<empty/>')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "binary_xml_table",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "binary_data", type: "bytea", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "xml_data", type: "xml", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["binary_xml_table": tableInfo]

        when: "filtering for non-null binary data"
        def environment = createMockEnvironment(
                ["id", "name", "binary_data"],
                ["binary_data_isNotNull": true]
        )
        def fetcher = dataFetcher.createTableDataFetcher("binary_xml_table")
        def result = fetcher.get(environment)

        then: "should return records with binary data"
        result.size() == 2
        result.every { it.name in ["Record 1", "Record 2"] }

        when: "filtering by XML content contains"
        environment = createMockEnvironment(
                ["id", "name", "xml_data"],
                ["xml_data_contains": "John"]
        )
        result = fetcher.get(environment)

        then: "should return records with XML containing 'John'"
        result.size() == 1
        result[0].name == "Record 1"

        when: "filtering by XML tag structure"
        environment = createMockEnvironment(
                ["id", "name", "xml_data"],
                ["xml_data_contains": "<product>"]
        )
        result = fetcher.get(environment)

        then: "should return records with XML containing product tag"
        result.size() == 1
        result[0].name == "Record 2"
    }

    def "should handle complex enhanced type filtering combinations"() {
        given: "a table with multiple enhanced types"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.enhanced_mixed (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                json_metadata JSON,
                price NUMERIC(10,2),
                created_at TIMESTAMPTZ,
                ip_address INET
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.enhanced_mixed (name, json_metadata, price, created_at, ip_address) VALUES 
            ('Item 1', '{"category": "electronics", "featured": true}', 1500.00, '2023-01-15 10:30:00+00', '192.168.1.1'),
            ('Item 2', '{"category": "books", "featured": false}', 25.99, '2023-02-20 15:45:00+00', '10.0.0.1'),
            ('Item 3', '{"category": "electronics", "featured": true}', 899.99, '2023-03-25 20:00:00+00', '2001:db8::1')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "enhanced_mixed",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "json_metadata", type: "json", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "price", type: "numeric", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_at", type: "timestamptz", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "ip_address", type: "inet", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["enhanced_mixed": tableInfo]

        when: "filtering with multiple enhanced type conditions"
        def environment = createMockEnvironment(
                ["id", "name", "json_metadata", "price"],
                [
                        "json_metadata_contains": "electronics",
                        "price_gte": 1000.00,
                        "created_at_gte": "2023-01-01T00:00:00Z"
                ]
        )
        def fetcher = dataFetcher.createTableDataFetcher("enhanced_mixed")
        def result = fetcher.get(environment)

        then: "should return items matching all enhanced type conditions"
        result.size() == 1
        result[0].name == "Item 1"

        when: "filtering with network type and JSON combination"
        environment = createMockEnvironment(
                ["id", "name", "json_metadata", "ip_address"],
                [
                        "ip_address_startsWith": "192.168",
                        "json_metadata_contains": "featured"
                ]
        )
        result = fetcher.get(environment)

        then: "should return items matching network and JSON conditions"
        result.size() == 1
        result[0].name == "Item 1"
    }

    // ========== PostgreSQL Array Types Tests ==========

    def "should handle integer array types"() {
        given: "a table with integer array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.integer_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                int_array INTEGER[],
                bigint_array BIGINT[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.integer_arrays (name, int_array, bigint_array) VALUES 
            ('Record 1', '{1,2,3,4,5}', '{100,200,300}'),
            ('Record 2', '{10,20,30}', '{1000,2000,3000,4000}'),
            ('Record 3', NULL, '{999}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "integer_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "bigint_array", type: "bigint[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["integer_arrays": tableInfo]

        when: "fetching all records"
        def environment = createMockEnvironment(
                ["id", "name", "int_array", "bigint_array"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("integer_arrays")
        def result = fetcher.get(environment)

        then: "should return arrays as Java Lists"
        result.size() == 3
        
        result[0].int_array == [1, 2, 3, 4, 5]
        result[0].bigint_array == [100L, 200L, 300L]
        
        result[1].int_array == [10, 20, 30]
        result[1].bigint_array == [1000L, 2000L, 3000L, 4000L]
        
        result[2].int_array == null
        result[2].bigint_array == [999L]

        when: "filtering by array content"
        environment = createMockEnvironment(
                ["id", "name", "int_array"],
                ["name": "Record 1"]
        )
        result = fetcher.get(environment)

        then: "should return record with correct array format"
        result.size() == 1
        result[0].int_array == [1, 2, 3, 4, 5]
        result[0].int_array instanceof List
        result[0].int_array.every { it instanceof Integer }
    }

    def "should handle text array types"() {
        given: "a table with text array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.text_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                text_array TEXT[],
                varchar_array VARCHAR(50)[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.text_arrays (name, text_array, varchar_array) VALUES 
            ('Record 1', '{"apple","banana","cherry"}', '{"red","green","blue"}'),
            ('Record 2', '{"hello","world"}', '{"foo","bar","baz"}'),
            ('Record 3', '{}', '{"single"}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "text_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "text_array", type: "text[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "varchar_array", type: "character varying[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["text_arrays": tableInfo]

        when: "fetching all records"
        def environment = createMockEnvironment(
                ["id", "name", "text_array", "varchar_array"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("text_arrays")
        def result = fetcher.get(environment)

        then: "should return string arrays as Java Lists"
        result.size() == 3
        
        result[0].text_array == ["apple", "banana", "cherry"]
        result[0].varchar_array == ["red", "green", "blue"]
        
        result[1].text_array == ["hello", "world"]
        result[1].varchar_array == ["foo", "bar", "baz"]
        
        result[2].text_array == []
        result[2].varchar_array == ["single"]

        and: "arrays should be proper Java Lists with String elements"
        result[0].text_array instanceof List
        result[0].text_array.every { it instanceof String }
        result[0].varchar_array instanceof List
        result[0].varchar_array.every { it instanceof String }
    }

    def "should handle boolean and numeric array types"() {
        given: "a table with boolean and numeric array columns"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.mixed_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                bool_array BOOLEAN[],
                decimal_array DECIMAL(5,2)[],
                float_array FLOAT[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.mixed_arrays (name, bool_array, decimal_array, float_array) VALUES 
            ('Record 1', '{true,false,true}', '{10.50,20.75,30.25}', '{1.1,2.2,3.3}'),
            ('Record 2', '{false,false}', '{99.99}', '{4.4,5.5}'),
            ('Record 3', '{true}', '{}', '{6.6}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "mixed_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "bool_array", type: "boolean[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "decimal_array", type: "decimal[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "float_array", type: "float[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["mixed_arrays": tableInfo]

        when: "fetching all records"
        def environment = createMockEnvironment(
                ["id", "name", "bool_array", "decimal_array", "float_array"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("mixed_arrays")
        def result = fetcher.get(environment)

        then: "should return properly typed arrays as Java Lists"
        result.size() == 3
        
        result[0].bool_array == [true, false, true]
        result[0].decimal_array == [10.50, 20.75, 30.25]
        result[0].float_array == [1.1, 2.2, 3.3]
        
        result[1].bool_array == [false, false]
        result[1].decimal_array == [99.99]
        result[1].float_array == [4.4, 5.5]
        
        result[2].bool_array == [true]
        result[2].decimal_array == []
        result[2].float_array == [6.6]

        and: "arrays should be proper Java Lists with correct element types"
        result[0].bool_array instanceof List
        result[0].bool_array.every { it instanceof Boolean }
        result[0].decimal_array instanceof List
        result[0].decimal_array.every { it instanceof Number }
        result[0].float_array instanceof List
        result[0].float_array.every { it instanceof Number }
    }

    def "should handle complex array scenarios with null values"() {
        given: "a table with arrays containing various null scenarios"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.null_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                mixed_int_array INTEGER[],
                nullable_text_array TEXT[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.null_arrays (name, mixed_int_array, nullable_text_array) VALUES 
            ('With Arrays', '{1,2,3}', '{"one","two","three"}'),
            ('Null Arrays', NULL, NULL),
            ('Empty Arrays', '{}', '{}'),
            ('Single Values', '{42}', '{"single"}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "null_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "mixed_int_array", type: "integer[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "nullable_text_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["null_arrays": tableInfo]

        when: "fetching all records"
        def environment = createMockEnvironment(
                ["id", "name", "mixed_int_array", "nullable_text_array"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("null_arrays")
        def result = fetcher.get(environment)

        then: "should handle null and empty arrays correctly"
        result.size() == 4
        
        // Record with proper arrays
        result[0].mixed_int_array == [1, 2, 3]
        result[0].nullable_text_array == ["one", "two", "three"]
        
        // Record with null arrays
        result[1].mixed_int_array == null
        result[1].nullable_text_array == null
        
        // Record with empty arrays
        result[2].mixed_int_array == []
        result[2].nullable_text_array == []
        
        // Record with single element arrays
        result[3].mixed_int_array == [42]
        result[3].nullable_text_array == ["single"]

        when: "filtering for non-null arrays"
        environment = createMockEnvironment(
                ["id", "name", "mixed_int_array"],
                ["mixed_int_array_isNotNull": true]
        )
        result = fetcher.get(environment)

        then: "should return records with non-null arrays"
        result.size() == 3
        result.every { it.mixed_int_array != null }
    }

    def "should handle array types in connection fetcher"() {
        given: "a table with array columns for connection testing"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.array_connections (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100),
                tags TEXT[],
                scores INTEGER[]
            )
        """)

        (1..5).each { i ->
            jdbcTemplate.execute("""
                INSERT INTO test_schema.array_connections (title, tags, scores) VALUES 
                ('Title ${i}', '{"tag${i}","common"}', '{${i},${i*10},${i*100}}')
            """)
        }

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "array_connections",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "title", type: "character varying(100)", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "tags", type: "text[]", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "scores", type: "integer[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["array_connections": tableInfo]

        when: "fetching connection with array fields"
        def environment = createMockConnectionEnvironment(
                ["id", "title", "tags", "scores"],
                [
                        "first": 3,
                        "orderBy": ["id": "ASC"]
                ]
        )
        def fetcher = dataFetcher.createConnectionDataFetcher("array_connections")
        def result = fetcher.get(environment)

        then: "should return connection with properly converted arrays"
        result.edges.size() == 3
        result.totalCount == 5

        def nodes = result.edges.collect { it.node }
        nodes[0].tags == ["tag1", "common"]
        nodes[0].scores == [1, 10, 100]
        nodes[1].tags == ["tag2", "common"]
        nodes[1].scores == [2, 20, 200]
        nodes[2].tags == ["tag3", "common"]
        nodes[2].scores == [3, 30, 300]

        and: "arrays should be proper Java Lists"
        nodes.every { node ->
            node.tags instanceof List && node.tags.every { it instanceof String } &&
            node.scores instanceof List && node.scores.every { it instanceof Integer }
        }
    }

    def "should handle arrays with special characters and escaping"() {
        given: "a table with arrays containing special characters"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.special_arrays (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                special_text_array TEXT[]
            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.special_arrays (name, special_text_array) VALUES 
            ('Quotes', '{"hello \\"world\\"","it''s working"}'),
            ('Commas', '{"item,with,commas","simple"}'),
            ('Backslashes', '{"path\\\\to\\\\file","another"}')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "special_arrays",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "character varying(100)", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "special_text_array", type: "text[]", primaryKey: false, nullable: true)
                ],
                foreignKeys: []
        )
        schemaReflector.reflectSchema() >> ["special_arrays": tableInfo]

        when: "fetching records with special characters in arrays"
        def environment = createMockEnvironment(
                ["id", "name", "special_text_array"],
                [:]
        )
        def fetcher = dataFetcher.createTableDataFetcher("special_arrays")
        def result = fetcher.get(environment)

        then: "should properly handle special characters in array elements"
        result.size() == 3
        
        // Arrays should be converted to proper Java Lists regardless of special characters
        result[0].special_text_array instanceof List
        result[1].special_text_array instanceof List
        result[2].special_text_array instanceof List
        
        // All elements should be strings
        result.every { record ->
            record.special_text_array.every { it instanceof String }
        }
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

    def "should handle forward foreign key relationships properly"() {
        given: "tables with foreign key relationships"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.customers (\n                customer_id SERIAL PRIMARY KEY,\n                first_name VARCHAR(45) NOT NULL,\n                last_name VARCHAR(45) NOT NULL,\n                email VARCHAR(50)\n            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.orders (\n                order_id SERIAL PRIMARY KEY,\n                customer_id INTEGER REFERENCES test_schema.customers(customer_id),\n                total_amount NUMERIC(10,2) NOT NULL,\n                order_date DATE DEFAULT CURRENT_DATE\n            )
        """)

        // Insert test data
        jdbcTemplate.execute("""
            INSERT INTO test_schema.customers (customer_id, first_name, last_name, email) VALUES \n            (1, 'John', 'Doe', 'john@example.com'),\n            (2, 'Jane', 'Smith', 'jane@example.com')
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.orders (order_id, customer_id, total_amount) VALUES\n            (1, 1, 299.99),\n            (2, 1, 149.50),\n            (3, 2, 89.99)
        """)

        and: "mocked schema reflector with foreign key info"
        def customersTableInfo = new TableInfo(
                name: "customers",
                columns: [
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "varchar", primaryKey: false, nullable: true)
                ],
                foreignKeys: [],
                view: false
        )

        def ordersTableInfo = new TableInfo(
                name: "orders",
                columns: [
                        new ColumnInfo(name: "order_id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "customer_id", type: "integer", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "total_amount", type: "numeric", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "order_date", type: "date", primaryKey: false, nullable: true)
                ],
                foreignKeys: [
                        new ForeignKeyInfo("customer_id", "customers", "customer_id")
                ],
                view: false
        )

        def mockReflector = Mock(IDatabaseSchemaReflector)
        mockReflector.reflectSchema() >> ["customers": customersTableInfo, "orders": ordersTableInfo]
        dataFetcher.schemaReflector = mockReflector

        when: "fetching orders with customer relationship"
        def customerField = Mock(SelectedField) {
            getName() >> "customers"
            getSelectionSet() >> Mock(DataFetchingFieldSelectionSet) {
                getFields() >> [
                        Mock(SelectedField) { getName() >> "customer_id" },
                        Mock(SelectedField) { getName() >> "first_name" },
                        Mock(SelectedField) { getName() >> "last_name" }
                ]
            }
        }
        
        def environment = Mock(DataFetchingEnvironment)
        def selectionSet = Mock(DataFetchingFieldSelectionSet)
        selectionSet.getFields() >> [
            Mock(SelectedField) { getName() >> "order_id" },
            Mock(SelectedField) { getName() >> "customer_id" },
            Mock(SelectedField) { getName() >> "total_amount" },
            customerField
        ]
        environment.getSelectionSet() >> selectionSet
        environment.getArguments() >> [:]
        environment.getGraphQlContext() >> GraphQLContext.newContext().build()

        def tableFetcher = dataFetcher.createTableDataFetcher("orders")
        def results = tableFetcher.get(environment)

        then: "should return orders data"
        results != null
        results.size() == 3
        results[0].order_id == 1
        results[0].total_amount == 299.99
        results[0].customer_id == 1

        and: "relationship data should be preloaded in batch context"
        def batchContext = environment.getGraphQlContext().get("BATCH_CONTEXT")
        batchContext != null
        batchContext["customers"] != null
    }

    def "should handle relationship data fetcher properly"() {
        given: "tables with foreign key relationships"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_customers (\n                customer_id SERIAL PRIMARY KEY,\n                first_name VARCHAR(45) NOT NULL,\n                last_name VARCHAR(45) NOT NULL\n            )
        """)

        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_orders (\n                order_id SERIAL PRIMARY KEY,\n                customer_id INTEGER REFERENCES test_schema.test_customers(customer_id),\n                total_amount NUMERIC(10,2) NOT NULL\n            )
        """)

        // Insert test data
        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_customers (customer_id, first_name, last_name) VALUES \n            (1, 'John', 'Doe'),\n            (2, 'Jane', 'Smith')
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_orders (order_id, customer_id, total_amount) VALUES\n            (1, 1, 299.99)
        """)

        and: "mocked schema reflector"
        def mockReflector = Mock(IDatabaseSchemaReflector)
        mockReflector.reflectSchema() >> [
                "test_customers": new TableInfo(
                        name: "test_customers",
                        columns: [
                                new ColumnInfo(name: "customer_id", type: "integer", primaryKey: true, nullable: false),
                                new ColumnInfo(name: "first_name", type: "varchar", primaryKey: false, nullable: false),
                                new ColumnInfo(name: "last_name", type: "varchar", primaryKey: false, nullable: false)
                        ],
                        foreignKeys: [],
                        view: false
                )
        ]
        dataFetcher.schemaReflector = mockReflector

        when: "using relationship data fetcher"
        def relationshipFetcher = dataFetcher.createRelationshipDataFetcher(
                "test_orders", "customer_id", "test_customers", "customer_id"
        )

        def mockEnvironment = Mock(DataFetchingEnvironment)
        def sourceData = ["customer_id": 1, "order_id": 1, "total_amount": 299.99]
        mockEnvironment.getSource() >> sourceData
        mockEnvironment.getGraphQlContext() >> Mock(GraphQLContext) {
            get("batchContext") >> null
        }

        def selectedFields = Mock(DataFetchingFieldSelectionSet)
        selectedFields.getFields() >> [
                Mock(SelectedField) { getName() >> "customer_id" },
                Mock(SelectedField) { getName() >> "first_name" },
                Mock(SelectedField) { getName() >> "last_name" }
        ]
        mockEnvironment.getSelectionSet() >> selectedFields

        def result = relationshipFetcher.get(mockEnvironment)

        then: "should return related customer data"
        result != null
        result.customer_id == 1
        result.first_name == "John"
        result.last_name == "Doe"
    }

    def "should handle connection pagination with required orderBy"() {
        given: "a table with data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.paginated_customers (\n                id SERIAL PRIMARY KEY,\n                name VARCHAR(100) NOT NULL,\n                email VARCHAR(100),\n                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.paginated_customers (id, name, email) VALUES \n            (1, 'Alice', 'alice@example.com'),\n            (2, 'Bob', 'bob@example.com'),\n            (3, 'Charlie', 'charlie@example.com'),\n            (4, 'David', 'david@example.com'),\n            (5, 'Eve', 'eve@example.com')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "paginated_customers",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "varchar", primaryKey: false, nullable: false),
                        new ColumnInfo(name: "email", type: "varchar", primaryKey: false, nullable: true),
                        new ColumnInfo(name: "created_at", type: "timestamp", primaryKey: false, nullable: true)
                ],
                foreignKeys: [],
                view: false
        )
        def mockReflector = Mock(IDatabaseSchemaReflector)
        mockReflector.reflectSchema() >> ["paginated_customers": tableInfo]
        dataFetcher.schemaReflector = mockReflector

        when: "fetching connection with orderBy"
        def environment = createMockConnectionEnvironment(
                ["id", "name", "email"],
                ["first": 3, "orderBy": ["id": "ASC"]]
        )
        def connectionFetcher = dataFetcher.createConnectionDataFetcher("paginated_customers")
        def result = connectionFetcher.get(environment)

        then: "should return properly structured connection result"
        result != null
        result.edges != null
        result.edges.size() == 3
        result.pageInfo != null
        result.pageInfo.hasNextPage == true
        result.pageInfo.hasPreviousPage == false
        result.totalCount == 5

        and: "edges should contain proper node data"
        result.edges[0].node.id == 1
        result.edges[0].node.name == "Alice"
        result.edges[1].node.id == 2
        result.edges[1].node.name == "Bob"
        result.edges[2].node.id == 3
        result.edges[2].node.name == "Charlie"
    }

    def "should handle connection pagination without orderBy gracefully"() {
        given: "a table with data"
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.no_order_customers (\n                id SERIAL PRIMARY KEY,\n                name VARCHAR(100) NOT NULL\n            )
        """)

        jdbcTemplate.execute("""
            INSERT INTO test_schema.no_order_customers (id, name) VALUES \n            (1, 'Alice'),\n            (2, 'Bob'),\n            (3, 'Charlie')
        """)

        and: "mocked schema reflector"
        def tableInfo = new TableInfo(
                name: "no_order_customers",
                columns: [
                        new ColumnInfo(name: "id", type: "integer", primaryKey: true, nullable: false),
                        new ColumnInfo(name: "name", type: "varchar", primaryKey: false, nullable: false)
                ],
                foreignKeys: [],
                view: false
        )
        def mockReflector = Mock(IDatabaseSchemaReflector)
        mockReflector.reflectSchema() >> ["no_order_customers": tableInfo]
        dataFetcher.schemaReflector = mockReflector

        when: "fetching connection without orderBy"
        def environment = createMockConnectionEnvironment(
                ["id", "name"],
                ["first": 2]
        )
        def connectionFetcher = dataFetcher.createConnectionDataFetcher("no_order_customers")
        def result = connectionFetcher.get(environment)

        then: "should return result with valid cursor values"
        result != null
        result.edges != null
        result.edges.size() == 2
        result.edges[0].cursor != null
        result.edges[0].cursor != "orderBy parameter is required for cursor-based pagination. Please provide a valid orderBy argument."
    }

    // TDD RED PHASE: Custom Type Fetching Tests
    def "should fetch records with custom enum values properly formatted"() {
        given: "a table with custom enum column"
        jdbcTemplate.execute("""
            CREATE TYPE test_priority AS ENUM ('low', 'medium', 'high', 'urgent')
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_tasks (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100),
                priority test_priority
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_tasks (title, priority) VALUES 
            ('Task 1', 'low'),
            ('Task 2', 'high'),
            ('Task 3', 'urgent')
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("title", "character varying", false, true),
            new ColumnInfo("priority", "test_priority", false, true)
        ]
        def tableInfo = new TableInfo("test_tasks", columns, [], false)

        def selectionSet = Mock(DataFetchingFieldSelectionSet) {
            getFields() >> [
                Mock(SelectedField) { getName() >> "id" },
                Mock(SelectedField) { getName() >> "title" },
                Mock(SelectedField) { getName() >> "priority" }
            ]
        }

        def environment = Mock(DataFetchingEnvironment) {
            getSelectionSet() >> selectionSet
            getArguments() >> [:]
            getGraphQLContext() >> GraphQLContext.newContext().build()
        }

        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_tasks": tableInfo]

        when: "creating and executing connection data fetcher"
        def fetcher = dataFetcher.createConnectionDataFetcher("test_tasks")
        def result = fetcher.get(environment)

        then: "should return properly formatted enum values"
        result != null
        result.edges != null
        result.edges.size() == 3
        
        // Check that enum values are returned as strings, not transformed
        def task1 = result.edges.find { it.node.title == 'Task 1' }
        task1.node.priority == 'low'
        
        def task2 = result.edges.find { it.node.title == 'Task 2' }
        task2.node.priority == 'high'
        
        def task3 = result.edges.find { it.node.title == 'Task 3' }
        task3.node.priority == 'urgent'

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_tasks")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_priority")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should fetch records with custom composite values as structured objects"() {
        given: "a table with custom composite column"
        jdbcTemplate.execute("""
            CREATE TYPE test_location AS (
                latitude DECIMAL(10,8),
                longitude DECIMAL(11,8),
                city VARCHAR(50)
            )
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_venues (
                id SERIAL PRIMARY KEY,
                name VARCHAR(100),
                location test_location
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_venues (name, location) VALUES 
            ('Venue 1', ROW(40.7589, -73.9851, 'New York')),
            ('Venue 2', ROW(34.0522, -118.2437, 'Los Angeles'))
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("name", "character varying", false, true),
            new ColumnInfo("location", "test_location", false, true)
        ]
        def tableInfo = new TableInfo("test_venues", columns, [], false)

        def selectionSet = Mock(DataFetchingFieldSelectionSet) {
            getFields() >> [
                Mock(SelectedField) { getName() >> "id" },
                Mock(SelectedField) { getName() >> "name" },
                Mock(SelectedField) { getName() >> "location" }
            ]
        }

        def environment = Mock(DataFetchingEnvironment) {
            getSelectionSet() >> selectionSet
            getArguments() >> [:]
            getGraphQLContext() >> GraphQLContext.newContext().build()
        }

        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_venues": tableInfo]

        when: "creating and executing connection data fetcher"
        def fetcher = dataFetcher.createConnectionDataFetcher("test_venues")
        def result = fetcher.get(environment)

        then: "should return structured composite objects"
        result != null
        result.edges != null
        result.edges.size() == 2
        
        // Check that composite values are parsed into structured objects
        def venue1 = result.edges.find { it.node.name == 'Venue 1' }
        venue1.node.location instanceof Map
        venue1.node.location.attr_0 == 40.7589    // latitude
        venue1.node.location.attr_1 == -73.9851   // longitude  
        venue1.node.location.attr_2 == 'New York' // city
        
        def venue2 = result.edges.find { it.node.name == 'Venue 2' }
        venue2.node.location instanceof Map
        venue2.node.location.attr_0 == 34.0522        // latitude
        venue2.node.location.attr_1 == -118.2437      // longitude
        venue2.node.location.attr_2 == 'Los Angeles'  // city

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_venues")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_location")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "should handle filtering on custom enum columns"() {
        given: "a table with custom enum column and data"
        jdbcTemplate.execute("""
            CREATE TYPE test_status AS ENUM ('draft', 'published', 'archived')
        """)
        
        jdbcTemplate.execute("""
            CREATE TABLE test_schema.test_articles (
                id SERIAL PRIMARY KEY,
                title VARCHAR(100),
                status test_status
            )
        """)
        
        jdbcTemplate.execute("""
            INSERT INTO test_schema.test_articles (title, status) VALUES 
            ('Article 1', 'draft'),
            ('Article 2', 'published'),
            ('Article 3', 'published'),
            ('Article 4', 'archived')
        """)

        def columns = [
            new ColumnInfo("id", "integer", true, false),
            new ColumnInfo("title", "character varying", false, true),
            new ColumnInfo("status", "test_status", false, true)
        ]
        def tableInfo = new TableInfo("test_articles", columns, [], false)

        def selectionSet = Mock(DataFetchingFieldSelectionSet) {
            getFields() >> [
                Mock(SelectedField) { getName() >> "id" },
                Mock(SelectedField) { getName() >> "title" },
                Mock(SelectedField) { getName() >> "status" }
            ]
        }

        def environment = Mock(DataFetchingEnvironment) {
            getSelectionSet() >> selectionSet
            getArguments() >> [
                where: [
                    status: [eq: 'published']
                ]
            ]
            getGraphQLContext() >> GraphQLContext.newContext().build()
        }

        and: "mocked schema reflector"
        schemaReflector.reflectSchema() >> ["test_articles": tableInfo]

        when: "creating and executing connection data fetcher"
        def fetcher = dataFetcher.createConnectionDataFetcher("test_articles")
        def result = fetcher.get(environment)

        then: "should filter by enum value correctly"
        result != null
        result.edges != null
        result.edges.size() == 2
        result.edges.every { it.node.status == 'published' }
        result.edges.any { it.node.title == 'Article 2' }
        result.edges.any { it.node.title == 'Article 3' }

        cleanup:
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS test_schema.test_articles")
            jdbcTemplate.execute("DROP TYPE IF EXISTS test_status")
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}