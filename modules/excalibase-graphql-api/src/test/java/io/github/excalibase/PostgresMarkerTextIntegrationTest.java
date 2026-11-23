package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Text a user stores is only text: no value of a column is ever read as the engine's own signal. (EXC-838) */
class PostgresMarkerTextIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";
    private static final String TEXT = "~excalibase_result_too_large~";

    @Override
    public Map<String, String> getProperties() {
        postgres.start();
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("CREATE TABLE note (id INT PRIMARY KEY, body TEXT)");
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    @Test
    void textThatLooksLikeAnEngineMarker_canBeWrittenAndReadBack() throws Exception {
        assertThat(http.postJson(REST + "note", Map.of("id", 1, "body", TEXT)).status()).isEqualTo(201);
        HttpAnswer represented = http.postJson(REST + "note", Map.of("id", 2, "body", TEXT), "Prefer", "return=representation");
        assertThat(represented.status()).as(represented.body()).isEqualTo(201);

        HttpAnswer read = http.get(REST + "note?order=id.asc");
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(read.body()).get("data").get(1).get("body").asText()).isEqualTo(TEXT);
    }
}
