package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * A variable placed inside an inline mutation input or filter writes and matches the value it holds on
 * real MySQL, whose create and update read their input through the same shared path as Postgres updates.
 */
class MysqlMutationVariablesIntegrationTest extends EngineIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-mysql-mutation-variables.sql");

    @Override
    public Map<String, String> getProperties() {
        mysql.start();
        Map<String, String> properties = EngineProperties.isolated();
        properties.put("spring.datasource.url", mysql.getJdbcUrl());
        properties.put("spring.datasource.username", mysql.getUsername());
        properties.put("spring.datasource.password", mysql.getPassword());
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        properties.put("app.database-type", "mysql");
        return properties;
    }


    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void update_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String, $p: Int, $tag: String) {
                  updateTestTasks(where: { title: { eq: $t } },
                                  input: { author: $a, status: $s, priority: $p, meta: { tag: $tag } }) { author }
                }""", vars("t", "seed-update", "a", "bob", "s", "doing", "p", 3, "tag", "x"))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.updateTestTasks[0].author").value("bob"));

        Map<String, Object> row = row("seed-update");
        assertThat(row.get("author")).isEqualTo("bob");
        assertThat(row.get("status")).isEqualTo("doing");
        assertThat(row.get("priority")).isEqualTo(3);
        assertThat(MAPPER.readTree(String.valueOf(row.get("meta")))).isEqualTo(MAPPER.readTree("{\"tag\":\"x\"}"));
    }

    @Test
    void update_unsetVariable_leavesTheColumnAlone() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String) {
                  updateTestTasks(where: { title: { eq: $t } }, input: { author: $a, status: $s }) { title }
                }""", vars("t", "seed-unset", "s", "done"))
                .expect(jsonPath("$.errors").doesNotExist());

        assertThat(row("seed-unset").get("author")).isEqualTo("alice");
        assertThat(row("seed-unset").get("status")).isEqualTo("done");
    }

    @Test
    void create_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String) {
                  createTestTasks(input: { title: $t, author: $a, status: $s }) { title author }
                }""", vars("t", "created-inline", "a", "dana", "s", "doing"))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createTestTasks.author").value("dana"));

        assertThat(row("created-inline").get("status")).isEqualTo("doing");
    }

    @Test
    void createMany_inlineRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($a: String, $s: String) {
                  createManyTestTasks(inputs: [{ title: "many-1", author: $a, status: $s }]) { title }
                }""", vars("a", "erin", "s", "done"))
                .expect(jsonPath("$.errors").doesNotExist());

        assertThat(row("many-1").get("author")).isEqualTo("erin");
        assertThat(row("many-1").get("status")).isEqualTo("done");
    }

    @Test
    void delete_whereVariableInsideList_matchesItsValue() throws Exception {
        perform("""
                mutation ($a: String!) {
                  deleteTestTasks(where: { author: { in: [$a] } }) { title }
                }""", vars("a", "carol"))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.deleteTestTasks[0].title").value("seed-delete"));

        assertThat(row("seed-delete")).isEmpty();
    }

    private HttpAnswer perform(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        return http.post("/test-proj/graphql", MAPPER.writeValueAsString(body), "Content-Type", "application/json")
                .expect(status().isOk());
    }

    private static Map<String, Object> vars(Object... pairs) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            variables.put((String) pairs[i], pairs[i + 1]);
        }
        return variables;
    }

    private static Map<String, Object> row(String title) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT author, status, priority, meta FROM tasks WHERE title = ?")) {
            statement.setString(1, title);
            try (ResultSet result = statement.executeQuery()) {
                Map<String, Object> row = new HashMap<>();
                if (result.next()) {
                    row.put("author", result.getString("author"));
                    row.put("status", result.getString("status"));
                    row.put("priority", result.getObject("priority"));
                    row.put("meta", result.getString("meta"));
                }
                return row;
            }
        }
    }
}
