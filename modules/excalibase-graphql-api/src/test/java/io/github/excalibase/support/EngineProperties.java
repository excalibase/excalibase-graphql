package io.github.excalibase.support;

import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** The settings every database-backed integration test needs; the container is started here. */
public final class EngineProperties {

    private EngineProperties() {
    }

    /** Settings every engine under test needs: no management port, so engines in one JVM never collide on it. */
    public static Map<String, String> isolated() {
        Map<String, String> properties = new HashMap<>();
        properties.put("endpoints.all.port", "-1");
        properties.put("micronaut.server.port", "-1");
        return properties;
    }

    public static Map<String, String> singleDatabase(JdbcDatabaseContainer<?> database, String databaseType,
                                                     String projectId) {
        database.start();
        Map<String, String> properties = isolated();
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("app.project-id", projectId);
        properties.put("app.database-type", databaseType);
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.max-rows", "30");
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
