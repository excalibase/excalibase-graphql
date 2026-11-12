package io.github.excalibase.support;

import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** The settings every database-backed integration test needs; the container is started here. */
public final class EngineProperties {

    private EngineProperties() {
    }

    /** Settings every engine under test needs: no management port and a free server port, so engines never collide. */
    public static Map<String, String> isolated() {
        Map<String, String> properties = new HashMap<>();
        properties.put("endpoints.all.port", "-1");
        properties.put("micronaut.server.port", "-1");
        return properties;
    }

    public static Map<String, String> singleDatabase(JdbcDatabaseContainer<?> database, String databaseType,
                                                     String projectId) {
        Map<String, String> properties = datasource(database);
        properties.put("app.project-id", projectId);
        properties.put("app.database-type", databaseType);
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.max-rows", "30");
        properties.put("endpoints.all.port", "-1");
        return properties;
    }

    /** Starts the database and names it as the engine's data source; the caller adds the rest. */
    public static Map<String, String> datasource(JdbcDatabaseContainer<?> database) {
        database.start();
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("micronaut.server.port", "-1");
        return properties;
    }

    /** An engine with no database behind it: enough for everything answered before a query runs. */
    public static Map<String, String> unreachableDatabase(String projectId) {
        Map<String, String> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent");
        properties.put("spring.datasource.username", "fake");
        properties.put("spring.datasource.password", "fake");
        properties.put("app.project-id", projectId);
        properties.put("app.database-type", "postgres");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("micronaut.server.port", "-1");
        properties.put("endpoints.all.port", "-1");
        return properties;
    }

    public static Map<String, String> postgres(JdbcDatabaseContainer<?> database, String projectId) {
        return singleDatabase(database, "postgres", projectId);
    }

    public static Map<String, String> mysql(JdbcDatabaseContainer<?> database, String projectId) {
        Map<String, String> properties = new HashMap<>(singleDatabase(database, "mysql", projectId));
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return properties;
    }
}
