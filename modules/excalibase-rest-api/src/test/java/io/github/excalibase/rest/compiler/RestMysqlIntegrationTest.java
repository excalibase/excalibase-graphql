package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.excalibase.mysql.MysqlDialect;
import io.github.excalibase.schema.SchemaInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The REST compiler's SQL run against a real MySQL: reads, embeds, filters, ordering and writes read back by key. */
@Testcontainers
class RestMysqlIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static TransactionTemplate tx;
    private static RestQueryCompiler compiler;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("""
            CREATE TABLE customer (
              customer_id INT AUTO_INCREMENT PRIMARY KEY,
              first_name VARCHAR(50) NOT NULL,
              email VARCHAR(100),
              score INT NULL,
              active TINYINT(1) DEFAULT 1,
              meta JSON NULL,
              created_at DATETIME NULL
            ) COLLATE utf8mb4_bin""");
        jdbc.execute("""
            CREATE TABLE orders (
              order_id INT AUTO_INCREMENT PRIMARY KEY,
              customer_id INT,
              total DECIMAL(10,2) NOT NULL,
              FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
            )""");
        jdbc.execute("""
            CREATE TABLE order_items (
              order_id INT NOT NULL,
              product_id INT NOT NULL,
              quantity INT NOT NULL,
              PRIMARY KEY (order_id, product_id),
              FOREIGN KEY (order_id) REFERENCES orders(order_id)
            )""");
        jdbc.execute("CREATE TABLE country (code VARCHAR(2) PRIMARY KEY, name VARCHAR(50) NOT NULL)");
        jdbc.execute("CREATE TABLE audit_log (message VARCHAR(100))");
        jdbc.execute("CREATE TABLE article (id INT PRIMARY KEY, body TEXT, FULLTEXT KEY article_body (body))");
        jdbc.execute("""
            CREATE TABLE member (
              id INT PRIMARY KEY,
              email VARCHAR(100),
              name VARCHAR(50),
              UNIQUE KEY member_email (email)
            )""");

        SchemaInfo schema = new SchemaInfo();
        column(schema, "customer", "customer_id", "int");
        column(schema, "customer", "first_name", "varchar");
        column(schema, "customer", "email", "varchar");
        column(schema, "customer", "score", "int");
        column(schema, "customer", "active", "tinyint");
        column(schema, "customer", "meta", "json");
        column(schema, "customer", "created_at", "datetime");
        schema.addPrimaryKey("test.customer", "customer_id");
        column(schema, "orders", "order_id", "int");
        column(schema, "orders", "customer_id", "int");
        column(schema, "orders", "total", "decimal");
        schema.addPrimaryKey("test.orders", "order_id");
        schema.addForeignKey("test.orders", "customer_id", "test.customer", "customer_id");
        column(schema, "order_items", "order_id", "int");
        column(schema, "order_items", "product_id", "int");
        column(schema, "order_items", "quantity", "int");
        schema.addPrimaryKey("test.order_items", "order_id");
        schema.addPrimaryKey("test.order_items", "product_id");
        schema.addForeignKey("test.order_items", "order_id", "test.orders", "order_id");
        column(schema, "country", "code", "varchar");
        column(schema, "country", "name", "varchar");
        schema.addPrimaryKey("test.country", "code");
        column(schema, "audit_log", "message", "varchar");
        column(schema, "article", "id", "int");
        column(schema, "article", "body", "text");
        schema.addPrimaryKey("test.article", "id");
        column(schema, "member", "id", "int");
        column(schema, "member", "email", "varchar");
        column(schema, "member", "name", "varchar");
        schema.addPrimaryKey("test.member", "id");
        compiler = new RestQueryCompiler(schema, new MysqlDialect(), "test", 100);
    }

    private static void column(SchemaInfo schema, String table, String column, String type) {
        schema.addColumn("test." + table, column, type);
        schema.setTableSchema("test." + table, "test");
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : List.of("order_items", "orders", "customer", "country", "audit_log", "member", "article")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("""
            INSERT INTO customer (first_name, email, score, active, meta, created_at) VALUES
              ('Alice', 'alice@example.com', 10, 1, '{"tier": "gold"}', '2026-01-01 10:00:00'),
              ('Bob', 'bob@example.com', NULL, 1, NULL, NULL),
              ('carol', NULL, 30, 0, NULL, NULL),
              ('Dave', 'dave@example.com', 20, 1, NULL, NULL)""");
        jdbc.update("INSERT INTO orders (customer_id, total) VALUES (1, 100.50), (1, 20.00), (2, 75.25)");
        jdbc.update("INSERT INTO order_items VALUES (1, 1, 2), (1, 2, 1), (2, 3, 5)");
        jdbc.update("INSERT INTO country VALUES ('VN', 'Vietnam')");
        jdbc.update("INSERT INTO article VALUES (1, 'postgres and mysql engines'), (2, 'cooking pasta')");
        jdbc.update("INSERT INTO member VALUES (1, 'a@example.com', 'Ann'), (2, 'b@example.com', 'Ben')");
    }

    private static RestQueryCompiler.FilterSpec filter(String column, String operator, String value) {
        return new RestQueryCompiler.FilterSpec(column, operator, value, false);
    }

    private static RestQueryCompiler.SelectQuery query(String table, List<String> columns,
                                                       List<RestQueryCompiler.FilterSpec> filters,
                                                       List<RestQueryCompiler.EmbedSpec> embeds,
                                                       List<RestQueryCompiler.OrderBySpec> order) {
        return new RestQueryCompiler.SelectQuery(table, columns, filters, List.of(), embeds, order, 100, 0, false);
    }

    private static List<Map<String, Object>> rows(RestQueryCompiler.CompiledResult compiled) throws Exception {
        Object body = named.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params())).get("body");
        return MAPPER.readValue(body.toString(), new TypeReference<>() {});
    }

    private static List<Object> column(List<Map<String, Object>> rows, String column) {
        return rows.stream().map(row -> row.get(column)).toList();
    }

    private static List<Map<String, Object>> select(String table, RestQueryCompiler.FilterSpec... filters) throws Exception {
        return rows(compiler.compileSelect(query(table, List.of(), List.of(filters), List.of(),
                List.of(new RestQueryCompiler.OrderBySpec(table.equals("test.customer") ? "customer_id" : "order_id",
                        "ASC", null)))));
    }

    private static String write(RestQueryCompiler.CompiledResult compiled, boolean represent) {
        assertNotNull(compiled.write(), "MySQL writes run as keyed steps");
        return tx.execute(status -> compiled.write().run(named, represent));
    }

    @Nested
    class Reads {

        @Test
        void selectsEveryColumnAsAJsonObject() throws Exception {
            List<Map<String, Object>> rows = select("test.customer");
            assertEquals(4, rows.size());
            assertEquals("Alice", rows.getFirst().get("first_name"));
            assertEquals(Map.of("tier", "gold"), rows.getFirst().get("meta"));
            assertEquals(Map.of("customer_id", 2, "first_name", "Bob"), rows(compiler.compileSelect(query(
                    "test.customer", List.of("customer_id", "first_name"), List.of(filter("customer_id", "eq", "2")),
                    List.of(), null))).getFirst());
        }

        @Test
        void comparesPatternsAndLists() throws Exception {
            assertEquals(List.of(3, 4), column(select("test.customer", filter("score", "gte", "20")), "customer_id"));
            assertEquals(List.of(1, 2), column(select("test.customer", filter("customer_id", "in", "(1,2)")), "customer_id"));
            assertEquals(List.of(3, 4), column(select("test.customer", filter("customer_id", "notin", "(1,2)")), "customer_id"));
            assertEquals(List.of(1), column(select("test.customer", filter("first_name", "like", "Al*")), "customer_id"));
            assertEquals(List.of(3), column(select("test.customer", filter("first_name", "ilike", "CAR*")), "customer_id"));
            assertEquals(List.of(2, 3, 4), column(select("test.customer",
                    new RestQueryCompiler.FilterSpec("first_name", "eq", "Alice", true)), "customer_id"));
            assertEquals(List.of(1, 2, 4), column(select("test.customer", filter("email", "isnotnull", "")), "customer_id"));
            assertEquals(List.of(3), column(select("test.customer", filter("email", "is", "null")), "customer_id"));
        }

        @Test
        void matchesRegexCaseSensitivelyOrNot() throws Exception {
            assertEquals(List.of(), column(select("test.customer", filter("first_name", "match", "^C")), "customer_id"));
            assertEquals(List.of(3), column(select("test.customer", filter("first_name", "imatch", "^C")), "customer_id"));
        }

        @Test
        void isDistinctTreatsNullAsAValue() throws Exception {
            assertEquals(List.of(1, 2, 3), column(select("test.customer", filter("score", "isdistinct", "20")), "customer_id"));
        }

        @Test
        void readsBooleansFromTinyint() throws Exception {
            assertEquals(List.of(3), column(select("test.customer", filter("active", "eq", "false")), "customer_id"));
            assertEquals(List.of(3), column(select("test.customer", filter("active", "is", "false")), "customer_id"));
        }

        @Test
        void ordersNullsFirstOrLast() throws Exception {
            var nullsFirst = rows(compiler.compileSelect(query("test.customer", List.of("customer_id"), List.of(), List.of(),
                    List.of(new RestQueryCompiler.OrderBySpec("score", "DESC", "NULLS FIRST")))));
            assertEquals(List.of(2, 3, 4, 1), column(nullsFirst, "customer_id"));
            var nullsLast = rows(compiler.compileSelect(query("test.customer", List.of("customer_id"), List.of(), List.of(),
                    List.of(new RestQueryCompiler.OrderBySpec("score", "ASC", "NULLS LAST")))));
            assertEquals(List.of(1, 4, 3, 2), column(nullsLast, "customer_id"));
        }

        @Test
        void pagesAndCounts() throws Exception {
            var compiled = compiler.compileSelect(new RestQueryCompiler.SelectQuery("test.customer", List.of("customer_id"),
                    List.of(filter("active", "eq", "true")), List.of(), List.of(),
                    List.of(new RestQueryCompiler.OrderBySpec("customer_id", "ASC", null)), 1, 1, true));
            Map<String, Object> result = named.queryForMap(compiled.sql(), new MapSqlParameterSource(compiled.params()));
            assertEquals(3L, ((Number) result.get("total_count")).longValue());
            assertEquals("[{\"customer_id\": 2}]", result.get("body").toString());
        }

        @Test
        void filtersByOrConditionsAndAfterCursor() throws Exception {
            var compiled = compiler.compileSelect(new RestQueryCompiler.SelectQuery("test.customer", List.of("customer_id"),
                    List.of(), List.of(new RestQueryCompiler.OrCondition(List.of(filter("customer_id", "eq", "1"),
                    filter("customer_id", "eq", "4"), filter("customer_id", "eq", "3")))), List.of(),
                    List.of(new RestQueryCompiler.OrderBySpec("customer_id", "ASC", null)), 10, 0, false, "1", "customer_id"));
            assertEquals(List.of(3, 4), column(rows(compiled), "customer_id"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void embedsToOneAndToManyNested() throws Exception {
            var items = new RestQueryCompiler.EmbedSpec("order_items", List.of("product_id", "quantity"));
            var orders = new RestQueryCompiler.EmbedSpec("orders", List.of("order_id", "total"), null, List.of(items));
            List<Map<String, Object>> customers = rows(compiler.compileSelect(query("test.customer",
                    List.of("customer_id"), List.of(), List.of(orders),
                    List.of(new RestQueryCompiler.OrderBySpec("customer_id", "ASC", null)))));
            var aliceOrders = (List<Map<String, Object>>) customers.getFirst().get("orders");
            assertEquals(2, aliceOrders.size());
            assertEquals(2, ((List<?>) aliceOrders.getFirst().get("order_items")).size());
            assertEquals(List.of(), customers.get(2).get("orders"));

            List<Map<String, Object>> withCustomer = rows(compiler.compileSelect(query("test.orders", List.of("order_id"),
                    List.of(filter("order_id", "eq", "3")), List.of(new RestQueryCompiler.EmbedSpec("customer",
                    List.of("first_name"))), null)));
            assertEquals(Map.of("first_name", "Bob"), withCustomer.getFirst().get("customer"));
        }

        @Test
        void refusesAnUnknownFilterColumn() {
            var typo = query("test.customer", List.of(), List.of(filter("frist_name", "eq", "Alice")), List.of(), null);
            var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compileSelect(typo));
            assertTrue(failure.getMessage().contains("'frist_name'"), failure.getMessage());
        }

        @Test
        @SuppressWarnings("unchecked")
        void runsJsonAndFullTextOperatorsNatively() throws Exception {
            assertEquals(List.of(1), column(select("test.customer", filter("meta", "cs", "{\"tier\": \"gold\"}")),
                    "customer_id"));
            assertEquals(List.of(1), column(select("test.customer", filter("meta", "haskey", "tier")), "customer_id"));
            var search = rows(compiler.compileSelect(query("test.article", List.of("id"),
                    List.of(filter("body", "plfts", "postgres")), List.of(), null)));
            assertEquals(List.of(1), column(search, "id"));
        }

        @Test
        void refusesOperatorsMysqlDoesNotHave() {
            for (var refused : List.of(filter("first_name", "wfts", "a"), filter("meta", "jsonpath", "$.a"),
                    filter("first_name", "ov", "{a}"), filter("first_name", "sl", "[1,2)"),
                    filter("first_name", "arraylength", "1"), filter("meta", "arrayhasany", "{a}"),
                    filter("meta", "vector", "{}"))) {
                var refusedQuery = query("test.customer", List.of(), List.of(refused), List.of(), null);
                var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compileSelect(refusedQuery),
                        refused.operator());
                assertTrue(failure.getMessage().contains("MySQL"), failure.getMessage());
            }
        }
    }

    @Nested
    class Writes {

        @Test
        @SuppressWarnings("unchecked")
        void insertReadsTheRowBackByItsGeneratedKey() throws Exception {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("first_name", "Erin");
            input.put("meta", Map.of("tier", "silver"));
            input.put("created_at", "2026-02-03 04:05:06");
            Map<String, Object> row = MAPPER.readValue(write(compiler.compileInsert("test.customer", input), true), Map.class);
            assertEquals(5, row.get("customer_id"));
            assertEquals("Erin", row.get("first_name"));
            assertEquals(Map.of("tier", "silver"), row.get("meta"));
            assertEquals(1, row.get("active"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void insertReadsTheRowBackByTheKeySent() throws Exception {
            Map<String, Object> row = MAPPER.readValue(write(compiler.compileInsert("test.country",
                    Map.of("code", "FR", "name", "France")), true), Map.class);
            assertEquals(Map.of("code", "FR", "name", "France"), row);
        }

        @Test
        void bulkInsertReadsEveryRowBack() throws Exception {
            String json = write(compiler.compileBulkInsert("test.customer",
                    List.of(Map.of("first_name", "Erin"), Map.of("first_name", "Finn", "score", 5))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(List.of(5, 6), column(rows, "customer_id"));
            assertEquals(List.of("Erin", "Finn"), column(rows, "first_name"));
        }

        @Test
        void bulkInsertWithCompositeKeys() throws Exception {
            String json = write(compiler.compileBulkInsert("test.order_items", List.of(
                    Map.of("order_id", 3, "product_id", 9, "quantity", 1),
                    Map.of("order_id", 3, "product_id", 8, "quantity", 4))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(2, rows.size());
            assertEquals(5, ((Number) jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class)).intValue());
        }

        @Test
        void keylessTableWritesButCannotRepresent() {
            assertNull(write(compiler.compileInsert("test.audit_log", Map.of("message", "hi")), false));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class));
            var again = compiler.compileInsert("test.audit_log", Map.of("message", "again"));
            var failure = assertThrows(IllegalArgumentException.class, () -> write(again, true));
            assertTrue(failure.getMessage().contains("primary key"), failure.getMessage());
        }

        @Test
        @SuppressWarnings("unchecked")
        void upsertUpdatesAnExistingRowAndInsertsANewOne() throws Exception {
            Map<String, Object> updated = MAPPER.readValue(write(compiler.compileUpsert("test.country",
                    Map.of("code", "VN", "name", "Viet Nam"), List.of("code")), true), Map.class);
            assertEquals("Viet Nam", updated.get("name"));
            Map<String, Object> inserted = MAPPER.readValue(write(compiler.compileUpsert("test.country",
                    Map.of("code", "JP", "name", "Japan"), List.of("code")), true), Map.class);
            assertEquals("Japan", inserted.get("name"));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM country", Integer.class));
        }

        @Test
        void upsertCollidingOnAnotherUniqueKeyIsAUniqueViolationLikePostgres() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("id", 2);
            input.put("email", "a@example.com");
            input.put("name", "Stolen");
            var upsert = compiler.compileUpsert("test.member", input, List.of("id"));
            var failure = assertThrows(DataAccessException.class, () -> write(upsert, true));
            assertEquals(1062, ((SQLException) failure.getMostSpecificCause()).getErrorCode());
            assertTrue(failure.getMostSpecificCause().getMessage().contains("member_email"));
            assertEquals(Map.of("id", 1, "email", "a@example.com", "name", "Ann"),
                    jdbc.queryForMap("SELECT id, email, name FROM member WHERE id = 1"));
            assertEquals("b@example.com", jdbc.queryForObject("SELECT email FROM member WHERE id = 2", String.class));

            input.put("id", 3);
            var insert = compiler.compileUpsert("test.member", input, List.of("id"));
            assertThrows(DataAccessException.class, () -> write(insert, true));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class));
        }

        @Test
        @SuppressWarnings("unchecked")
        void upsertKeepingItsOwnUniqueValueUpdates() throws Exception {
            Map<String, Object> row = MAPPER.readValue(write(compiler.compileUpsert("test.member",
                    Map.of("id", 1, "email", "a@example.com", "name", "Anna"), List.of("id")), true), Map.class);
            assertEquals("Anna", row.get("name"));
        }

        @Test
        void upsertOfOnlyTheKeyDoesNothingOnConflict() {
            assertNull(write(compiler.compileUpsert("test.country", Map.of("code", "VN"), List.of("code")), true));
        }

        @Test
        void updateReadsBackTheRowsItChanged() throws Exception {
            String json = write(compiler.compileUpdate("test.customer", Map.of("score", 99),
                    List.of(filter("active", "eq", "true"), filter("score", "is", "null"))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(List.of(2), column(rows, "customer_id"));
            assertEquals(List.of(99), column(rows, "score"));
        }

        @Test
        void updateFollowsAChangedKey() throws Exception {
            String json = write(compiler.compileUpdate("test.country", Map.of("code", "VI"),
                    List.of(filter("code", "eq", "VN"))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(List.of("VI"), column(rows, "code"));
        }

        @Test
        void updateWhoseFilterNoLongerMatchesStillReturnsTheRows() throws Exception {
            String json = write(compiler.compileUpdate("test.customer", Map.of("active", false),
                    List.of(filter("active", "eq", "true"))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(List.of(1, 2, 4), column(rows, "customer_id"));
            assertEquals(List.of(0, 0, 0), column(rows, "active"));
        }

        @Test
        void deleteReturnsTheRowsItRemoved() throws Exception {
            String json = write(compiler.compileDelete("test.order_items", List.of(filter("order_id", "eq", "1"))), true);
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<>() {});
            assertEquals(List.of(1, 2), column(rows, "product_id"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Integer.class));
        }

        @Test
        void writesWithoutRepresentationReturnNothing() {
            assertNull(write(compiler.compileUpdate("test.customer", Map.of("score", 1),
                    List.of(filter("customer_id", "eq", "1"))), false));
            assertNull(write(compiler.compileDelete("test.audit_log", List.of(filter("message", "eq", "x"))), false));
            assertEquals(1, jdbc.queryForObject("SELECT score FROM customer WHERE customer_id = 1", Integer.class));
        }

        @Test
        void functionsAreRefused() {
            var functionQuery = query("test.customer", List.of(), List.of(), List.of(), null);
            Map<String, Object> params = new LinkedHashMap<>();
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> compiler.compileFunctionSelect(functionQuery, "f()", params, false));
            assertTrue(failure.getMessage().contains("MySQL"), failure.getMessage());
        }
    }
}
