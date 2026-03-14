package io.github.excalibase.postgres.reflector;

import io.github.excalibase.config.AppConfig;
import io.github.excalibase.model.StoredProcedureInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for stored procedure discovery in PostgreSQL.
 */
@Testcontainers
class PostgresStoredProcedureReflectorTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
    private PostgresDatabaseSchemaReflectorImplement reflector;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUsername(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(ds);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    total       NUMERIC(10,2) NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE OR REPLACE PROCEDURE get_customer_order_count(
                    IN  p_customer_id BIGINT,
                    OUT p_count       BIGINT
                )
                LANGUAGE plpgsql AS $$
                BEGIN
                    SELECT COUNT(*) INTO p_count FROM orders WHERE customer_id = p_customer_id;
                END;
                $$
                """);

        jdbcTemplate.execute("""
                CREATE OR REPLACE PROCEDURE update_order_total(
                    IN p_order_id BIGINT,
                    IN p_total    NUMERIC
                )
                LANGUAGE plpgsql AS $$
                BEGIN
                    UPDATE orders SET total = p_total WHERE order_id = p_order_id;
                END;
                $$
                """);

        AppConfig appConfig = mock(AppConfig.class);
        when(appConfig.getAllowedSchema()).thenReturn("public");
        when(appConfig.getCache()).thenReturn(new AppConfig.CacheConfig());
        reflector = new PostgresDatabaseSchemaReflectorImplement(jdbcTemplate, appConfig);
    }

    @Test
    void shouldDiscoverStoredProcedures() {
        List<StoredProcedureInfo> procedures = reflector.discoverStoredProcedures("public");

        assertThat(procedures).isNotEmpty();
        List<String> names = procedures.stream().map(StoredProcedureInfo::getName).toList();
        assertThat(names).contains("get_customer_order_count", "update_order_total");
    }

    @Test
    void shouldDiscoverProcedureParameters() {
        List<StoredProcedureInfo> procedures = reflector.discoverStoredProcedures("public");

        StoredProcedureInfo proc = procedures.stream()
                .filter(p -> "get_customer_order_count".equals(p.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(proc.getParameters()).hasSize(2);

        StoredProcedureInfo.ProcedureParam inParam = proc.getParameters().stream()
                .filter(p -> "IN".equals(p.getMode()))
                .findFirst()
                .orElseThrow();
        assertThat(inParam.getName()).isEqualTo("p_customer_id");

        StoredProcedureInfo.ProcedureParam outParam = proc.getParameters().stream()
                .filter(p -> "OUT".equals(p.getMode()))
                .findFirst()
                .orElseThrow();
        assertThat(outParam.getName()).isEqualTo("p_count");
    }

    @Test
    void shouldDiscoverProceduresWithDefaultSchemaMethod() {
        List<StoredProcedureInfo> procedures = reflector.discoverStoredProcedures();

        assertThat(procedures).isNotEmpty();
    }
}
