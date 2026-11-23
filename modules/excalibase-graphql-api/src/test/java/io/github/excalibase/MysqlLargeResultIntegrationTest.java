package io.github.excalibase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A big REST result is answered whole or refused loudly, never as an empty 200. A wide table's rows add up to more
 * than any single value MySQL hands back by default. (EXC-838)
 */
class MysqlLargeResultIntegrationTest extends EngineIntegrationTest {

    /** MySQL's max_allowed_packet for this run, which bounds the one value a result is built as. */
    String packetBytes() {
        return "1048576";
    }

    private static final int COLUMNS = 301;
    private static final int ROWS = 1500;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "/test-proj/api/v1/";

    @Override
    public Map<String, String> getProperties() {
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withCommand("--max-allowed-packet=" + packetBytes());
        mysql.start();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl() + "?sessionVariables=cte_max_recursion_depth=100000", mysql.getUsername(),
                mysql.getPassword()));
        List<String> definitions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int column = 1; column <= COLUMNS; column++) {
            definitions.add("c" + column + " VARCHAR(20)");
            names.add("c" + column);
            values.add("CONCAT('v', n, '-', " + column + ")");
        }
        jdbc.execute("CREATE TABLE note (id INT PRIMARY KEY, body TEXT)");
        jdbc.execute("CREATE TABLE big (id INT PRIMARY KEY, tag VARCHAR(10), a LONGTEXT, b LONGTEXT)");
        jdbc.execute("INSERT INTO big VALUES (1, 't', REPEAT('x', 700000), REPEAT('y', 700000))");
        jdbc.execute("CREATE TABLE owner (id INT PRIMARY KEY)");
        jdbc.execute("INSERT INTO owner VALUES (1)");
        definitions.add("owner_id INT");
        jdbc.execute("CREATE TABLE wide (id INT PRIMARY KEY, " + String.join(", ", definitions)
                + ", FOREIGN KEY (owner_id) REFERENCES owner (id)) DEFAULT CHARSET latin1");
        jdbc.execute("INSERT INTO wide (id, owner_id, " + String.join(", ", names) + ") WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL"
                + " SELECT n + 1 FROM seq WHERE n < " + ROWS + ") SELECT n, 1, " + String.join(", ", values) + " FROM seq");
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.max-rows", "1000");
        properties.put("app.database-type", "mysql");
        return properties;
    }

    /** The rows asked for, all of them, or an error with a status and a code: never a 200 that holds nothing. */
    private void assertWholeOrLoudlyRefused(int limit) throws Exception {
        HttpAnswer answer = http.get(REST + "wide?select=*&order=id.asc&limit=" + limit);
        if (answer.status() == 200) {
            JsonNode data = MAPPER.readTree(answer.body()).get("data");
            assertThat(data.size()).as("rows in a 200 for limit " + limit + " (" + answer.body().length() + " bytes)")
                    .isEqualTo(limit);
            assertThat(data.get(limit - 1).get("id").asInt()).isEqualTo(limit);
        } else {
            assertThat(answer.status()).as(answer.body()).isEqualTo(413);
            assertThat(MAPPER.readTree(answer.body()).get("code").asText()).isEqualTo("result_too_large");
        }
    }

    @Test
    void aBigResult_isNeverAnEmpty200_atAnySize() throws Exception {
        for (int limit : new int[] {50, 100, 200, 300, 400, 600, 1000}) {
            assertWholeOrLoudlyRefused(limit);
        }
    }

    @Test
    void graphql_aBigResult_isAnErrorWithACode_notAnEmptyList() throws Exception {
        List<String> fields = new ArrayList<>();
        for (int column = 1; column <= COLUMNS; column++) {
            fields.add("c" + column);
        }
        HttpAnswer answer = http.graphql("/test-proj/graphql", "{ testWide(limit: 1000, orderBy: { id: ASC }) { id "
                + String.join(" ", fields) + " } }");
        JsonNode body = MAPPER.readTree(answer.body());
        if (body.has("errors")) {
            assertThat(body.get("errors").get(0).path("extensions").path("code").asText()).as(answer.body())
                    .isEqualTo("result_too_large");
        } else {
            assertThat(body.path("data").path("testWide").size()).as(answer.body().substring(0, Math.min(300, answer.body().length()))).isEqualTo(1000);
        }
    }

    @Test
    void anEmbeddedArrayTooBigToBuild_isAnErrorNotAnEmptyList() throws Exception {
        HttpAnswer answer = http.get(REST + "owner?select=id,wide(*)");
        if (answer.status() == 200) {
            assertThat(MAPPER.readTree(answer.body()).get("data").get(0).get("wide").size()).isEqualTo(ROWS);
        } else {
            assertThat(answer.status()).as(answer.body()).isEqualTo(413);
            assertThat(MAPPER.readTree(answer.body()).get("code").asText()).isEqualTo("result_too_large");
        }
    }

    @Test
    void aNarrowSelectOfTheSameRows_isStillWhole() throws Exception {
        HttpAnswer answer = http.get(REST + "wide?select=id,c1&order=id.asc&limit=1000");
        assertThat(answer.status()).isEqualTo(200);
        assertThat(MAPPER.readTree(answer.body()).get("data").size()).isEqualTo(1000);
    }

    private void assertRefusedAsTooLarge(HttpAnswer answer) throws Exception {
        assertThat(answer.status()).as(answer.body()).isEqualTo(413);
        assertThat(MAPPER.readTree(answer.body()).get("code").asText()).isEqualTo("result_too_large");
    }

    @Test
    void aSingleRowTooBigForOneAnswer_isRefused_notAnEmptyList() throws Exception {
        assertRefusedAsTooLarge(http.get(REST + "big?id=eq.1"));
    }

    @Test
    void aWriteWhoseRepresentationIsTooBig_isRefused_andNothingIsWritten() throws Exception {
        assertRefusedAsTooLarge(http.patchJson(REST + "big?id=eq.1", Map.of("tag", "u"), "Prefer", "return=representation"));

        HttpAnswer after = http.get(REST + "big?id=eq.1&select=id,tag");
        assertThat(MAPPER.readTree(after.body()).get("data").get(0).get("tag").asText()).isEqualTo("t");
    }

    @Test
    void textThatLooksLikeAnEngineMarker_isJustText() throws Exception {
        String text = "~excalibase_result_too_large~";
        assertThat(http.postJson(REST + "note", Map.of("id", 1, "body", text)).status()).isEqualTo(201);

        HttpAnswer read = http.get(REST + "note");
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(read.body()).get("data").get(0).get("body").asText()).isEqualTo(text);
    }
}
