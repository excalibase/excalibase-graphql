package io.github.excalibase;

import io.github.excalibase.support.EngineProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.testcontainers.containers.MySQLContainer;

/** {@link SchemaRefreshContract} on Mysql. */
class MysqlSchemaRefreshIntegrationTest extends SchemaRefreshContract {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Override
    public Map<String, String> getProperties() {
        return withFastPoll(EngineProperties.mysql(mysql, "test-proj"));
    }

    @Override
    void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
