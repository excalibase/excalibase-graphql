package io.github.excalibase.admin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A document that names a table or column the live database lacks is valid and saves, and says what the engine will
 * drop: the tables may be created after the document, but until then the role holds less than the text says.
 */
class AdminValidateWarningsIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT);
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-never-expose.sql");

    private static final String HEAD = "format: 1\nprojectId: proj-admin\ntables:\n";
    private static final String FITS = HEAD + "  - table: public.t\n    role: user\n    select:\n      filter: {}\n      columns: [id, label]\n";
    private static final String NO_TABLE = HEAD + "  - table: public.ghosts\n    role: user\n    select:\n      filter: {}\n      columns: \"*\"\n";
    private static final String NO_COLUMN = HEAD + "  - table: public.t\n    role: user\n    select:\n      filter: {}\n      columns: [id, nope]\n";

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        Map<String, String> properties = AdminTestEngine.consoleOn(FILE, -1, ADMIN_PORT);
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.database-type", "postgres");
        return properties;
    }

    private HttpAnswer post(String path, String body) {
        return admin.post(path, body, HEADER, SECRET, "Content-Type", "text/plain");
    }

    private HttpAnswer save(String body) {
        String version = admin.get("/_admin/v1/metadata", HEADER, SECRET).jsonPath("$.sourceVersion");
        return admin.send("PUT", "/_admin/v1/metadata", body, HEADER, SECRET, "Content-Type", "text/plain",
                "If-Match", "\"" + version + "\"");
    }

    @Test
    @DisplayName("validate warns about a table the database lacks, and the document stays valid")
    void unknownTable() {
        HttpAnswer answer = post("/_admin/v1/validate", NO_TABLE);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.<Boolean>jsonPath("$.valid")).isTrue();
        JsonNode warning = answer.json().path("warnings").path(0);
        assertThat(warning.path("code").asText()).isEqualTo("unknown_table");
        assertThat(warning.path("table").asText()).isEqualTo("public.ghosts");
        assertThat(warning.path("role").asText()).isEqualTo("user");
        assertThat(warning.path("message").asText()).contains("does not exist in the database yet", "no access");
    }

    @Test
    @DisplayName("validate warns about a column the table lacks, naming the operation and the column")
    void unknownColumn() {
        HttpAnswer answer = post("/_admin/v1/validate", NO_COLUMN);

        assertThat(answer.<Boolean>jsonPath("$.valid")).isTrue();
        JsonNode warning = answer.json().path("warnings").path(0);
        assertThat(warning.path("code").asText()).isEqualTo("unknown_column");
        assertThat(warning.path("operation").asText()).isEqualTo("select");
        assertThat(warning.path("message").asText()).contains("nope", "cannot select");
    }

    @Test
    @DisplayName("validate of a document that fits has no warnings key")
    void fits() {
        HttpAnswer answer = post("/_admin/v1/validate", FITS);

        assertThat(answer.<Boolean>jsonPath("$.valid")).isTrue();
        assertThat(answer.json().has("warnings")).isFalse();
    }

    @Test
    @DisplayName("parse carries the same warnings beside the tree")
    void parseCarriesWarnings() {
        HttpAnswer answer = post("/_admin/v1/parse", NO_TABLE);

        assertThat(answer.json().path("tree").isObject()).isTrue();
        assertThat(answer.json().path("warnings").path(0).path("code").asText()).isEqualTo("unknown_table");
    }

    @Test
    @DisplayName("a save with a warning is a 200 that writes the file and carries the warning")
    void saveNeverBlocks() throws IOException {
        HttpAnswer answer = save(NO_TABLE);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.json().path("warnings").path(0).path("code").asText()).isEqualTo("unknown_table");
        assertThat(Files.readString(FILE)).isEqualTo(NO_TABLE);
        assertThat(save(AdminTestEngine.DOCUMENT).status()).isEqualTo(200);
    }
}
