package io.github.excalibase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A variable placed inside an inline mutation input or filter writes and matches the value it holds on
 * real MySQL, whose create and update read their input through the same shared path as Postgres updates.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlMutationVariablesIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withInitScript("init-mysql-mutation-variables.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
        registry.add("app.database-type", () -> "mysql");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void update_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String, $p: Int, $tag: String) {
                  updateTestTasks(where: { title: { eq: $t } },
                                  input: { author: $a, status: $s, priority: $p, meta: { tag: $tag } }) { author }
                }""", vars("t", "seed-update", "a", "bob", "s", "doing", "p", 3, "tag", "x"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.updateTestTasks[0].author").value("bob"));

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
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(row("seed-unset").get("author")).isEqualTo("alice");
        assertThat(row("seed-unset").get("status")).isEqualTo("done");
    }

    @Test
    void create_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String) {
                  createTestTasks(input: { title: $t, author: $a, status: $s }) { title author }
                }""", vars("t", "created-inline", "a", "dana", "s", "doing"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createTestTasks.author").value("dana"));

        assertThat(row("created-inline").get("status")).isEqualTo("doing");
    }

    @Test
    void createMany_inlineRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($a: String, $s: String) {
                  createManyTestTasks(inputs: [{ title: "many-1", author: $a, status: $s }]) { title }
                }""", vars("a", "erin", "s", "done"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(row("many-1").get("author")).isEqualTo("erin");
        assertThat(row("many-1").get("status")).isEqualTo("done");
    }

    @Test
    void delete_whereVariableInsideList_matchesItsValue() throws Exception {
        perform("""
                mutation ($a: String!) {
                  deleteTestTasks(where: { author: { in: [$a] } }) { title }
                }""", vars("a", "carol"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.deleteTestTasks[0].title").value("seed-delete"));

        assertThat(row("seed-delete")).isEmpty();
    }

    private ResultActions perform(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        return mockMvc.perform(post("/test-proj/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());
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
