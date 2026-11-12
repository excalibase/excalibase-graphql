package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.idl.SchemaPrinter;
import io.github.excalibase.schema.GraphqlSchemaManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Postgres schema a client introspects, printed whole and compared with the committed snapshot: building filter
 * inputs from dialect capabilities must not change a single byte of what Postgres advertises.
 */
@SpringBootTest
@Testcontainers
class PostgresSchemaSnapshotTest {

    private static final String SNAPSHOT = "snapshots/postgres-filter-schema.graphqls";
    private static final String INTROSPECTION_SNAPSHOT = "snapshots/postgres-filter-introspection.json";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-filter-capabilities.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.allowed-schema", () -> "filters");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
    }

    @Autowired
    private GraphqlSchemaManager schemaManager;

    @Test
    void postgresSchemaMatchesTheSnapshot() throws IOException {
        String printed = new SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false))
                .print(schemaManager.getEngineState().introspectionHandler().getSchema());
        assertMatchesSnapshot(printed, SNAPSHOT);
    }

    /** The printed schema sorts fields; the introspection answer keeps the order a client receives them in. */
    @Test
    void postgresIntrospectionMatchesTheSnapshot() throws IOException {
        Map<String, Object> answer = schemaManager.getEngineState().introspectionHandler()
                .execute(IntrospectionQuery.INTROSPECTION_QUERY, Map.of());
        String printed = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(answer) + "\n";
        assertMatchesSnapshot(printed, INTROSPECTION_SNAPSHOT);
    }

    private void assertMatchesSnapshot(String printed, String snapshot) throws IOException {
        Path actual = Path.of("target", Path.of(snapshot).getFileName() + ".actual");
        Files.createDirectories(actual.getParent());
        Files.writeString(actual, printed);
        try (InputStream expected = getClass().getClassLoader().getResourceAsStream(snapshot)) {
            assertThat(expected).as("snapshot %s (actual written to %s)", snapshot, actual).isNotNull();
            assertThat(printed).isEqualTo(new String(expected.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
