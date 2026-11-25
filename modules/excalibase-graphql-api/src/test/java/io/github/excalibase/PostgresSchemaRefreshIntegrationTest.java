package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/** {@link SchemaRefreshContract} on Postgres. */
class PostgresSchemaRefreshIntegrationTest extends SchemaRefreshContract {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    public Map<String, String> getProperties() {
        return withFastPoll(EngineProperties.postgres(postgres, "test-proj"));
    }

    @Override
    void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
