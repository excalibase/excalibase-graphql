package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.idl.SchemaPrinter;
import io.github.excalibase.schema.GraphqlSchemaManager;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

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
class PostgresSchemaSnapshotTest extends EngineIntegrationTest {

    private static final String SNAPSHOT = "snapshots/postgres-filter-schema.graphqls";
    private static final String INTROSPECTION_SNAPSHOT = "snapshots/postgres-filter-introspection.json";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-filter-capabilities.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.allowed-schema", "filters");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    @Inject
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
