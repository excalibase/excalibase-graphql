package io.github.excalibase.mysql.fetcher;

import graphql.GraphQLContext;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import io.github.excalibase.mysql.reflector.MysqlDatabaseSchemaReflectorImplement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link MysqlDatabaseDataFetcherImplement}.
 */
@Testcontainers
class MysqlDatabaseDataFetcherImplementTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
    private MysqlDatabaseDataFetcherImplement fetcher;
    private MysqlDatabaseSchemaReflectorImplement reflector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(mysql.getJdbcUrl());
        ds.setUsername(mysql.getUsername());
        ds.setPassword(mysql.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    price DECIMAL(10,2),
                    in_stock TINYINT(1) DEFAULT 1
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS categories (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    label VARCHAR(100) NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS product_categories (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    product_id BIGINT NOT NULL,
                    category_id BIGINT NOT NULL,
                    CONSTRAINT fk_pc_product FOREIGN KEY (product_id) REFERENCES products(id),
                    CONSTRAINT fk_pc_category FOREIGN KEY (category_id) REFERENCES categories(id)
                )
                """);

        jdbcTemplate.execute("INSERT INTO products (name, price, in_stock) VALUES ('Widget', 9.99, 1)");
        jdbcTemplate.execute("INSERT INTO products (name, price, in_stock) VALUES ('Gadget', 19.99, 0)");
        jdbcTemplate.execute("INSERT INTO products (name, price, in_stock) VALUES ('Doohickey', 4.99, 1)");
        jdbcTemplate.execute("INSERT INTO categories (label) VALUES ('Electronics')");
        jdbcTemplate.execute("INSERT INTO categories (label) VALUES ('Hardware')");
        jdbcTemplate.execute("INSERT INTO product_categories (product_id, category_id) VALUES (1, 1)");
        jdbcTemplate.execute("INSERT INTO product_categories (product_id, category_id) VALUES (2, 1)");

        reflector = new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "testdb");
        fetcher = new MysqlDatabaseDataFetcherImplement(jdbcTemplate, reflector);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS product_categories");
        jdbcTemplate.execute("DROP TABLE IF EXISTS products");
        jdbcTemplate.execute("DROP TABLE IF EXISTS categories");
    }

    @Test
    void shouldFetchAllRows() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of());

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(3);
    }

    @Test
    void shouldFetchRowsWithLimit() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of("limit", 2));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(2);
    }

    @Test
    void shouldFetchRowsWithOffset() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of("limit", 10, "offset", 2));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(1);
    }

    @Test
    void shouldFilterWithEqualityCondition() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of(
                "where", Map.of("name", Map.of("eq", "Widget"))));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst()).containsEntry("name", "Widget");
    }

    @Test
    void shouldOrderByColumn() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of(
                "orderBy", Map.of("price", "ASC")));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(3);
        assertThat(results.getFirst()).containsEntry("name", "Doohickey");
    }

    @Test
    void shouldReturnConnectionWithPageInfo() throws Exception {
        DataFetcher<Map<String, Object>> df = fetcher.buildConnectionDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of("first", 2));

        Map<String, Object> connection = df.get(env);

        assertThat(connection).containsKeys("edges", "pageInfo");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat((Boolean) pageInfo.get("hasNextPage")).isTrue();
    }

    @Test
    void shouldResolveRelationship() throws Exception {
        // product_categories.category_id → categories.id
        DataFetcher<Map<String, Object>> df = fetcher.buildRelationshipDataFetcher(
                "product_categories", "category_id", "categories", "id");

        Map<String, Object> source = Map.of("id", 1L, "product_id", 1L, "category_id", 1L);
        DataFetchingEnvironment env = mockEnvWithSource(Map.of(), source);

        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull().containsEntry("label", "Electronics");
    }

    @Test
    void shouldResolveReverseRelationship() throws Exception {
        // categories → product_categories where category_id = categories.id
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildReverseRelationshipDataFetcher(
                "categories", "product_categories", "category_id", "id");

        Map<String, Object> source = Map.of("id", 1L, "label", "Electronics");
        DataFetchingEnvironment env = mockEnvWithSource(Map.of(), source);

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(2);
    }

    @Test
    void shouldBuildAggregateDataFetcher() throws Exception {
        DataFetcher<Map<String, Object>> df = fetcher.buildAggregateDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of());

        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull()
                .containsKey("count");
        assertThat(((Number) result.get("count")).longValue()).isEqualTo(3L);
    }

    @Test
    void shouldComputeAggregateOnDecimalColumnNotPrimaryKey() throws Exception {
        // products: id(bigint PK) = 1,2,3  price(decimal) = 9.99, 19.99, 4.99
        // sum of IDs = 6  |  sum of prices = 34.97
        // If aggregate picks the PK, sum = 6. If it picks price, sum ≈ 34.97.
        DataFetcher<Map<String, Object>> df = fetcher.buildAggregateDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of());

        Map<String, Object> result = df.get(env);

        double sum = ((Number) result.get("sum")).doubleValue();
        double avg = ((Number) result.get("avg")).doubleValue();

        // price sum = 9.99+19.99+4.99 = 34.97, NOT id sum = 6
        assertThat(sum).isGreaterThan(6.0);
        assertThat(avg).isGreaterThan(2.0);
    }

    @Test
    void shouldUseForwardBatchContextWhenPreLoaded() throws Exception {
        // Forward FK: product_categories.category_id → categories.id
        // When BATCH_CONTEXT has categories pre-loaded, should return without hitting DB
        int[] queryCount = {0};
        DataSource ds = jdbcTemplate.getDataSource();
        JdbcTemplate countingJdbc = new JdbcTemplate(ds) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.toLowerCase().contains("categories")) queryCount[0]++;
                return super.queryForList(sql, args);
            }
        };
        MysqlDatabaseDataFetcherImplement countingFetcher =
                new MysqlDatabaseDataFetcherImplement(countingJdbc, reflector);

        Map<Object, Map<String, Object>> preloaded = new HashMap<>();
        preloaded.put(1L, Map.of("id", 1L, "label", "Electronics"));
        Map<String, Object> batchContextMap = new HashMap<>();
        batchContextMap.put("categories", preloaded);
        GraphQLContext ctx = GraphQLContext.newContext().build();
        ctx.put("BATCH_CONTEXT", batchContextMap);

        DataFetchingEnvironment env = mockEnvWithSourceAndContext(
                Map.of(), Map.of("id", 1L, "product_id", 1L, "category_id", 1L), ctx);

        DataFetcher<Map<String, Object>> df = countingFetcher.buildRelationshipDataFetcher(
                "product_categories", "category_id", "categories", "id");
        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull().containsEntry("label", "Electronics");
        assertThat(queryCount[0]).as("should use batch context — zero DB queries").isZero();
    }

    @Test
    void shouldPreloadForwardFkInBatchContext() throws Exception {
        // When querying product_categories with 'categories' in selection set,
        // should fire exactly 1 query to categories (not one per row)
        int[] categoriesQueryCount = {0};
        DataSource ds = jdbcTemplate.getDataSource();
        JdbcTemplate countingJdbc = new JdbcTemplate(ds) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.toLowerCase().contains("categories") && !sql.toLowerCase().contains("product_categories"))
                    categoriesQueryCount[0]++;
                return super.queryForList(sql, args);
            }
        };
        MysqlDatabaseDataFetcherImplement countingFetcher =
                new MysqlDatabaseDataFetcherImplement(countingJdbc, reflector);

        GraphQLContext ctx = GraphQLContext.newContext().build();
        DataFetchingEnvironment env = mockEnvWithSelectionAndContext(Map.of(), List.of("categories"), ctx);

        DataFetcher<List<Map<String, Object>>> df = countingFetcher.buildTableDataFetcher("product_categories");
        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(2);
        assertThat(categoriesQueryCount[0])
                .as("exactly 1 batch query to categories (not 1 per row)").isEqualTo(1);
        assertThat(ctx.<Map<String, Object>>get("BATCH_CONTEXT")).containsKey("categories");
    }

    @Test
    void shouldUseReverseBatchContextWhenPreLoaded() throws Exception {
        // Reverse FK: categories → product_categories where category_id = categories.id
        int[] queryCount = {0};
        DataSource ds = jdbcTemplate.getDataSource();
        JdbcTemplate countingJdbc = new JdbcTemplate(ds) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.toLowerCase().contains("product_categories")) queryCount[0]++;
                return super.queryForList(sql, args);
            }
        };
        MysqlDatabaseDataFetcherImplement countingFetcher =
                new MysqlDatabaseDataFetcherImplement(countingJdbc, reflector);

        Map<Object, List<Map<String, Object>>> grouped = new HashMap<>();
        grouped.put(1L, List.of(
                Map.of("id", 1L, "product_id", 1L, "category_id", 1L),
                Map.of("id", 2L, "product_id", 2L, "category_id", 1L)
        ));
        Map<String, Object> batchContextMap = new HashMap<>();
        batchContextMap.put("REV:product_categories:category_id", grouped);
        GraphQLContext ctx = GraphQLContext.newContext().build();
        ctx.put("BATCH_CONTEXT", batchContextMap);

        DataFetchingEnvironment env = mockEnvWithSourceAndContext(
                Map.of(), Map.of("id", 1L, "label", "Electronics"), ctx);

        DataFetcher<List<Map<String, Object>>> df = countingFetcher.buildReverseRelationshipDataFetcher(
                "categories", "product_categories", "category_id", "id");
        List<Map<String, Object>> result = df.get(env);

        assertThat(result).hasSize(2);
        assertThat(queryCount[0]).as("should use reverse batch context — zero DB queries").isZero();
    }

    @Test
    void shouldPreloadReverseFkInBatchContext() throws Exception {
        // When querying categories with 'productCategories' in selection set,
        // should fire exactly 1 batch query to product_categories
        int[] pcQueryCount = {0};
        DataSource ds = jdbcTemplate.getDataSource();
        JdbcTemplate countingJdbc = new JdbcTemplate(ds) {
            @Override
            public List<Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql.toLowerCase().contains("product_categories")) pcQueryCount[0]++;
                return super.queryForList(sql, args);
            }
        };
        MysqlDatabaseDataFetcherImplement countingFetcher =
                new MysqlDatabaseDataFetcherImplement(countingJdbc, reflector);

        GraphQLContext ctx = GraphQLContext.newContext().build();
        // 'productCategories' = toLowerCamelCase("product_categories") ends in 's' so stays
        DataFetchingEnvironment env = mockEnvWithSelectionAndContext(
                Map.of(), List.of("productCategories"), ctx);

        DataFetcher<List<Map<String, Object>>> df = countingFetcher.buildTableDataFetcher("categories");
        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(2); // categories: Electronics, Hardware
        assertThat(pcQueryCount[0])
                .as("exactly 1 batch query to product_categories (not 1 per category)").isEqualTo(1);
        assertThat(ctx.<Map<String, Object>>get("BATCH_CONTEXT"))
                .containsKey("REV:product_categories:category_id");
    }

    @Test
    void shouldConnectionRespectWhereFilter() throws Exception {
        // Only "Widget" matches eq filter — connection should return 1 edge, not all 3
        DataFetcher<Map<String, Object>> df = fetcher.buildConnectionDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of(
                "first", 10,
                "where", Map.of("name", Map.of("eq", "Widget"))));

        Map<String, Object> connection = df.get(env);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) edges.get(0).get("node");
        assertThat(node).containsEntry("name", "Widget");
    }

    @Test
    void shouldConnectionRespectOrderBy() throws Exception {
        // price ASC: Doohickey(4.99) < Widget(9.99) < Gadget(19.99)
        DataFetcher<Map<String, Object>> df = fetcher.buildConnectionDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of(
                "first", 3,
                "orderBy", Map.of("price", "ASC")));

        Map<String, Object> connection = df.get(env);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode).containsEntry("name", "Doohickey");
    }

    @Test
    void shouldFilterWithOrCondition() throws Exception {
        // or: [{name: {eq: "Widget"}}, {name: {eq: "Gadget"}}] → 2 results
        DataFetcher<List<Map<String, Object>>> df = fetcher.buildTableDataFetcher("products");
        DataFetchingEnvironment env = mockEnv(Map.of(
                "where", Map.of(
                        "or", List.of(
                                Map.of("name", Map.of("eq", "Widget")),
                                Map.of("name", Map.of("eq", "Gadget"))
                        ))));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(2);
        List<Object> names = results.stream().map(r -> r.get("name")).toList();
        assertThat(names).containsExactlyInAnyOrder("Widget", "Gadget");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DataFetchingEnvironment mockEnv(Map<String, Object> args) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(args);
        when(env.getArgument(anyString())).thenAnswer(inv -> args.get(inv.getArgument(0)));
        when(env.containsArgument(anyString())).thenAnswer(inv -> args.containsKey(inv.getArgument(0)));
        when(env.getSelectionSet()).thenReturn(null);
        when(env.getSource()).thenReturn(null);
        when(env.getGraphQlContext()).thenReturn(GraphQLContext.newContext().build());
        return env;
    }

    private DataFetchingEnvironment mockEnvWithSource(Map<String, Object> args, Map<String, Object> source) {
        DataFetchingEnvironment env = mockEnv(args);
        when(env.getSource()).thenReturn(source);
        return env;
    }

    private DataFetchingEnvironment mockEnvWithSourceAndContext(
            Map<String, Object> args, Map<String, Object> source, GraphQLContext ctx) {
        DataFetchingEnvironment env = mockEnvWithSource(args, source);
        when(env.getGraphQlContext()).thenReturn(ctx);
        return env;
    }

    private DataFetchingEnvironment mockEnvWithSelectionAndContext(
            Map<String, Object> args, List<String> relationshipFields, GraphQLContext ctx) {
        DataFetchingEnvironment env = mockEnv(args);
        when(env.getGraphQlContext()).thenReturn(ctx);

        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        List<SelectedField> fields = new ArrayList<>();
        for (String fieldName : relationshipFields) {
            SelectedField sf = mock(SelectedField.class);
            when(sf.getName()).thenReturn(fieldName);
            DataFetchingFieldSelectionSet nested = mock(DataFetchingFieldSelectionSet.class);
            when(nested.getFields()).thenReturn(List.of());
            when(sf.getSelectionSet()).thenReturn(nested);
            fields.add(sf);
        }
        when(selectionSet.getFields()).thenReturn(fields);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        return env;
    }
}
