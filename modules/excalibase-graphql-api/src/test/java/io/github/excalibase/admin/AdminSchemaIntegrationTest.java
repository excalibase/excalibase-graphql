package io.github.excalibase.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reflected schema, on a database whose internal schemas hold tables and functions and a public table keeps
 * a foreign key into them: the admin view lists the public objects and none of the internal ones.
 */
class AdminSchemaIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-never-expose.sql");

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, ADMIN_PORT);
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        return properties;
    }

    private static List<String> names(JsonNode array, String field) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.path(field).asText()));
        return names;
    }

    @Test
    @DisplayName("GET schema lists the public tables with their columns")
    void listsPublicTables() {
        HttpAnswer answer = admin.get("/_admin/v1/schema", HEADER, SECRET);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(names(answer.json().path("tables"), "name")).contains("items", "t");
        JsonNode items = null;
        for (JsonNode table : answer.json().path("tables")) {
            if ("items".equals(table.path("name").asText())) {
                items = table;
            }
        }
        assertThat(items).isNotNull();
        assertThat(names(items.path("columns"), "name")).contains("id", "name", "meta_id", "user_id");
    }

    @Test
    @DisplayName("GET schema never lists excalibase_meta, auth or excalibase, nor their functions")
    void excludesReservedSchemas() {
        HttpAnswer answer = admin.get("/_admin/v1/schema", HEADER, SECRET);

        assertThat(names(answer.json().path("tables"), "schema")).doesNotContain("excalibase_meta", "auth", "excalibase");
        assertThat(names(answer.json().path("functions"), "schema")).doesNotContain("excalibase_meta", "auth", "excalibase");
        assertThat(names(answer.json().path("functions"), "name")).contains("plain_items")
                .doesNotContain("leak_meta", "read_t", "read_users");
        assertThat(answer.body()).doesNotContain("excalibase_meta").doesNotContain("password_hash")
                .doesNotContain("users");
    }
}
