package io.github.excalibase.mysql.reflector;

import io.github.excalibase.model.StoredProcedureInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for stored procedure discovery in MySQL.
 */
@Testcontainers
class MysqlStoredProcedureReflectorTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private JdbcTemplate jdbcTemplate;
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
                CREATE TABLE IF NOT EXISTS orders (
                    order_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    total       DECIMAL(10,2) NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE PROCEDURE IF NOT EXISTS get_customer_order_count(
                    IN  p_customer_id BIGINT,
                    OUT p_count       INT
                )
                BEGIN
                    SELECT COUNT(*) INTO p_count FROM orders WHERE customer_id = p_customer_id;
                END
                """);

        jdbcTemplate.execute("""
                CREATE PROCEDURE IF NOT EXISTS update_order_status(
                    IN p_order_id BIGINT,
                    IN p_status   VARCHAR(20)
                )
                BEGIN
                    UPDATE orders SET total = total WHERE order_id = p_order_id;
                END
                """);

        reflector = new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "testdb");
    }

    @Test
    void shouldDiscoverStoredProcedures() {
        List<StoredProcedureInfo> procedures = reflector.discoverStoredProcedures("testdb");

        assertThat(procedures).isNotEmpty();
        List<String> names = procedures.stream().map(StoredProcedureInfo::getName).toList();
        assertThat(names).contains("get_customer_order_count", "update_order_status");
    }

    @Test
    void shouldDiscoverProcedureParameters() {
        List<StoredProcedureInfo> procedures = reflector.discoverStoredProcedures("testdb");

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
        assertThat(inParam.getDataType()).isEqualTo("bigint");

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

    @Test
    void shouldReturnEmptyListWhenNoProceduresExist() {
        MysqlDatabaseSchemaReflectorImplement emptyReflector =
                new MysqlDatabaseSchemaReflectorImplement(jdbcTemplate, "information_schema");

        List<StoredProcedureInfo> procedures = emptyReflector.discoverStoredProcedures("information_schema");

        assertThat(procedures).isEmpty();
    }
}
