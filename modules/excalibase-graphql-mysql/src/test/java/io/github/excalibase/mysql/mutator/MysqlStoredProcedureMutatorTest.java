package io.github.excalibase.mysql.mutator;

import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.github.excalibase.model.StoredProcedureInfo;
import io.github.excalibase.mysql.reflector.MysqlDatabaseSchemaReflectorImplement;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for stored procedure execution via MysqlDatabaseMutatorImplement.
 */
@Testcontainers
class MysqlStoredProcedureMutatorTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
    private MysqlDatabaseMutatorImplement mutator;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(mysql.getJdbcUrl());
        ds.setUsername(mysql.getUsername());
        ds.setPassword(mysql.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    total       DECIMAL(10,2) NOT NULL
                )
                """);

        jdbcTemplate.execute("INSERT INTO orders (customer_id, total) VALUES (1, 100.00)");
        jdbcTemplate.execute("INSERT INTO orders (customer_id, total) VALUES (1, 200.00)");
        jdbcTemplate.execute("INSERT INTO orders (customer_id, total) VALUES (2, 50.00)");

        jdbcTemplate.execute("""
                CREATE PROCEDURE IF NOT EXISTS get_customer_order_count(
                    IN  p_customer_id BIGINT,
                    OUT p_count       INT
                )
                BEGIN
                    SELECT COUNT(*) INTO p_count FROM orders WHERE customer_id = p_customer_id;
                END
                """);

        MysqlDatabaseSchemaReflectorImplement reflector =
                new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "testdb");
        mutator = new MysqlDatabaseMutatorImplement(jdbcTemplate, reflector);
    }

    @Test
    void shouldCallProcedureWithInAndOutParams() throws Exception {
        StoredProcedureInfo.ProcedureParam inParam =
                new StoredProcedureInfo.ProcedureParam("p_customer_id", "bigint", "IN", 1);
        StoredProcedureInfo.ProcedureParam outParam =
                new StoredProcedureInfo.ProcedureParam("p_count", "int", "OUT", 2);
        StoredProcedureInfo proc = new StoredProcedureInfo(
                "get_customer_order_count", "testdb", List.of(inParam, outParam));

        DataFetcher<Object> fetcher = mutator.buildProcedureMutationResolver(proc);

        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(Map.of("p_customer_id", 1));

        Object result = fetcher.get(env);

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsKey("p_count");
        assertThat(((Number) resultMap.get("p_count")).intValue()).isEqualTo(2);
    }

    @Test
    void shouldCallProcedureWithOnlyInParams() throws Exception {
        jdbcTemplate.execute("""
                CREATE PROCEDURE IF NOT EXISTS reset_customer_orders(
                    IN p_customer_id BIGINT
                )
                BEGIN
                    UPDATE orders SET total = 0.00 WHERE customer_id = p_customer_id AND 1=0;
                END
                """);

        StoredProcedureInfo.ProcedureParam inParam =
                new StoredProcedureInfo.ProcedureParam("p_customer_id", "bigint", "IN", 1);
        StoredProcedureInfo proc = new StoredProcedureInfo(
                "reset_customer_orders", "testdb", List.of(inParam));

        DataFetcher<Object> fetcher = mutator.buildProcedureMutationResolver(proc);

        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getArguments()).thenReturn(Map.of("p_customer_id", 1));

        Object result = fetcher.get(env);

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsKey("success");
        assertThat(resultMap).containsEntry("success", true);
    }
}
