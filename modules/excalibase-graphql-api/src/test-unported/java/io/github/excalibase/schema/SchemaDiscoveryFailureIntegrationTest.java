package io.github.excalibase.schema;

import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.config.datasource.TenantDatabase;
import io.github.excalibase.service.DatabaseEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A database being replaced by a restore drops the first connection a request takes. Reflecting the
 * project then must fail the request and cache nothing, so the next request sees the real tables
 * instead of an empty schema served until something unrelated evicts it.
 */
@Testcontainers
class SchemaDiscoveryFailureIntegrationTest {

    private static final String PROJECT = "proj_restore01";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("shop");

    /** Fails the first connection, as the pool does when the server behind it was just replaced. */
    private static final class FirstConnectionDropped extends DelegatingDataSource {
        private final AtomicBoolean dropped = new AtomicBoolean();

        FirstConnectionDropped(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (dropped.compareAndSet(false, true)) {
                throw new SQLTransientConnectionException("Communications link failure");
            }
            return super.getConnection();
        }
    }

    private static DataSource dataSource(JdbcDatabaseContainer<?> container) {
        return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static GraphqlSchemaManager managerOver(DataSource dataSource, DatabaseEngine engine) {
        DynamicDataSourceManager tenants = mock(DynamicDataSourceManager.class);
        when(tenants.getDatabase("acme", PROJECT)).thenReturn(new TenantDatabase(dataSource, engine));
        return new GraphqlSchemaManager(null, 30, "postgres", GraphqlSchemaManager.DEFAULT_MAX_QUERY_DEPTH, 30,
                "", null, tenants, null, false);
    }

    private static void assertFailureIsNotCached(DataSource real, DatabaseEngine engine, String table) {
        GraphqlSchemaManager manager = managerOver(new FirstConnectionDropped(real), engine);

        assertThatThrownBy(() -> manager.resolveEngineState("acme", PROJECT, null))
                .isInstanceOf(SchemaUnavailableException.class);

        SchemaInfo served = manager.resolveEngineState("acme", PROJECT, null).compiler().schemaInfo();
        assertThat(served.getTableNames()).anyMatch(name -> name.endsWith("." + table));
    }

    @Test
    @DisplayName("Postgres: a reflection whose connection drops fails, and the next request sees the tables")
    void postgres_droppedConnection_failsAndIsNotCached() {
        DataSource real = dataSource(postgres);
        new JdbcTemplate(real).execute("CREATE TABLE IF NOT EXISTS app_orders (id serial PRIMARY KEY, sku text)");

        assertFailureIsNotCached(real, DatabaseEngine.POSTGRES, "app_orders");
    }

    @Test
    @DisplayName("MySQL: a reflection whose connection drops fails, and the next request sees the tables")
    void mysql_droppedConnection_failsAndIsNotCached() {
        DataSource real = dataSource(mysql);
        new JdbcTemplate(real).execute("CREATE TABLE IF NOT EXISTS app_orders (id INT AUTO_INCREMENT PRIMARY KEY, sku VARCHAR(40))");

        assertFailureIsNotCached(real, DatabaseEngine.MYSQL, "app_orders");
    }
}
