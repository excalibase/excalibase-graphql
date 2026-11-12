package io.github.excalibase.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Direct access to a test database, for arranging and checking rows behind the engine's back. */
public final class DatabaseAccess {

    private DatabaseAccess() {
    }

    public static JdbcTemplate template(JdbcDatabaseContainer<?> database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(),
                database.getPassword()));
    }
}
