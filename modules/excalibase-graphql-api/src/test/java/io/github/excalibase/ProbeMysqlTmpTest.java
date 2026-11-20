package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import graphql.schema.idl.SchemaPrinter;
import io.github.excalibase.schema.GraphqlSchemaManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.nio.file.*;
import java.util.Map;

class ProbeMysqlTmpTest extends EngineIntegrationTest {
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-filters.sql");
    @Override
    public Map<String, String> getProperties() {
        mysql.start();
        Map<String, String> p = EngineProperties.isolated();
        p.put("spring.datasource.url", mysql.getJdbcUrl());
        p.put("spring.datasource.username", mysql.getUsername());
        p.put("spring.datasource.password", mysql.getPassword());
        p.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        p.put("app.project-id", "test-proj");
        p.put("app.security.jwt-enabled", "false");
        p.put("app.security.insecure-dev-mode", "true");
        p.put("app.database-type", "mysql");
        return p;
    }
    @Inject GraphqlSchemaManager schemaManager;
    @Test void dump() throws Exception {
        String printed = new SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false))
                .print(schemaManager.getEngineState().introspectionHandler().getSchema());
        Files.writeString(Path.of("/tmp/claude-1000/mysql-schema.graphqls"), printed);
    }
}
