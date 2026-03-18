package io.github.excalibase.mysql.mutator;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.mysql.reflector.MysqlDatabaseSchemaReflectorImplement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link MysqlDatabaseMutatorImplement}.
 */
@Testcontainers
class MysqlDatabaseMutatorImplementTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
    private MysqlDatabaseMutatorImplement mutator;
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
                CREATE TABLE IF NOT EXISTS items (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    price DECIMAL(10,2),
                    active TINYINT(1) DEFAULT 1
                )
                """);

        reflector = new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "testdb");
        mutator = new MysqlDatabaseMutatorImplement(jdbcTemplate, reflector);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS items");
    }

    @Test
    void shouldCreateRecord() throws Exception {
        DataFetcher<Map<String, Object>> df = mutator.buildCreateMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithInput(Map.of("name", "Widget", "price", 9.99));

        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("Widget");
        assertThat(result.get("id")).isNotNull();
    }

    @Test
    void shouldReturnGeneratedId() throws Exception {
        DataFetcher<Map<String, Object>> df = mutator.buildCreateMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithInput(Map.of("name", "Gadget", "price", 19.99));

        Map<String, Object> result = df.get(env);

        assertThat(result.get("id")).isNotNull();
        long id = ((Number) result.get("id")).longValue();
        assertThat(id).isGreaterThan(0L);
    }

    @Test
    void shouldUpdateRecord() throws Exception {
        // Insert first
        jdbcTemplate.execute("INSERT INTO items (name, price) VALUES ('Old Name', 5.00)");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM items WHERE name = 'Old Name'", Long.class);

        DataFetcher<Map<String, Object>> df = mutator.buildUpdateMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithInputAndId(id, Map.of("name", "New Name"));

        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("New Name");
        assertThat(((Number) result.get("id")).longValue()).isEqualTo(id);
    }

    @Test
    void shouldDeleteRecord() throws Exception {
        jdbcTemplate.execute("INSERT INTO items (name, price) VALUES ('ToDelete', 1.00)");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM items WHERE name = 'ToDelete'", Long.class);

        DataFetcher<Map<String, Object>> df = mutator.buildDeleteMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithId(id);

        Map<String, Object> result = df.get(env);

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("ToDelete");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM items WHERE id = ?", Integer.class, id);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void shouldBulkCreateRecords() throws Exception {
        DataFetcher<List<Map<String, Object>>> df = mutator.buildBulkCreateMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithInputList(List.of(
                Map.of("name", "Bulk1", "price", 1.00),
                Map.of("name", "Bulk2", "price", 2.00),
                Map.of("name", "Bulk3", "price", 3.00)));

        List<Map<String, Object>> results = df.get(env);

        assertThat(results).hasSize(3);
        assertThat(results.stream().map(r -> r.get("name")).toList())
                .containsExactlyInAnyOrder("Bulk1", "Bulk2", "Bulk3");
    }

    @Test
    void shouldPersistCreatedRecord() throws Exception {
        DataFetcher<Map<String, Object>> df = mutator.buildCreateMutationResolver("items");
        DataFetchingEnvironment env = mockEnvWithInput(Map.of("name", "Persisted", "price", 42.0));
        df.get(env);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM items WHERE name = 'Persisted'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DataFetchingEnvironment mockEnvWithInput(Map<String, Object> input) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArgument("input")).thenReturn(input);
        when(env.getArguments()).thenReturn(Map.of("input", input));
        when(env.containsArgument(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return "input".equals(key);
        });
        return env;
    }

    private DataFetchingEnvironment mockEnvWithInputAndId(Long id, Map<String, Object> input) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArgument("id")).thenReturn(id);
        when(env.getArgument("input")).thenReturn(input);
        when(env.getArguments()).thenReturn(Map.of("id", id, "input", input));
        when(env.containsArgument(anyString())).thenReturn(true);
        return env;
    }

    private DataFetchingEnvironment mockEnvWithId(Long id) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArgument("id")).thenReturn(id);
        when(env.getArguments()).thenReturn(Map.of("id", id));
        when(env.containsArgument(anyString())).thenReturn(true);
        return env;
    }

    private DataFetchingEnvironment mockEnvWithInputList(List<Map<String, Object>> inputs) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArgument("inputs")).thenReturn(inputs);
        when(env.getArguments()).thenReturn(Map.of("inputs", inputs));
        when(env.containsArgument(anyString())).thenReturn(true);
        return env;
    }
}
