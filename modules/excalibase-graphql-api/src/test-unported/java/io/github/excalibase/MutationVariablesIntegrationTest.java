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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A variable placed anywhere inside an inline mutation input or filter writes and matches the value it
 * holds, on real Postgres: create, createMany, update, updateCollection, upsert, nested insert and the
 * where filters of update and delete. An input field given an unset variable is left out, as GraphQL says.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MutationVariablesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-mutation-variables.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.project-id", () -> "test-proj");
        registry.add("app.security.jwt-enabled", () -> "false");
        registry.add("app.security.insecure-dev-mode", () -> "true");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void update_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String, $p: Int) {
                  updatePublicTasks(where: { title: { eq: $t } }, input: { author: $a, status: $s, priority: $p }) {
                    author status priority
                  }
                }""", vars("t", "seed-update", "a", "bob", "s", "doing", "p", 3))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.updatePublicTasks[0].author").value("bob"));

        Map<String, Object> row = row("seed-update");
        assertThat(row.get("author")).isEqualTo("bob");
        assertThat(row.get("status")).isEqualTo("doing");
        assertThat(row.get("priority")).isEqualTo(3);
    }

    @Test
    void update_jsonbObjectWithNestedVariable_storesTheValue() throws Exception {
        perform("""
                mutation ($t: String!, $tag: String) {
                  updatePublicTasks(where: { title: { eq: $t } }, input: { meta: { tag: $tag, n: 1 } }) { id }
                }""", vars("t", "seed-update", "tag", "hot \"quoted\""))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(MAPPER.readTree(String.valueOf(row("seed-update").get("meta"))))
                .isEqualTo(MAPPER.readTree("{\"tag\":\"hot \\\"quoted\\\"\",\"n\":1}"));
    }

    @Test
    void update_unsetVariable_leavesTheColumnAlone() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String) {
                  updatePublicTasks(where: { title: { eq: $t } }, input: { author: $a, status: $s }) { id }
                }""", vars("t", "seed-unset", "s", "done"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        Map<String, Object> row = row("seed-unset");
        assertThat(row.get("author")).isEqualTo("alice");
        assertThat(row.get("status")).isEqualTo("done");
    }

    @Test
    void create_inlineInputVariables_includingInsideJsonb_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String, $tag: String, $list: [String]) {
                  createPublicTasks(input: { title: $t, author: $a, status: $s, meta: { tag: $tag, list: $list } }) {
                    title author status
                  }
                }""", vars("t", "created-inline", "a", "dana", "s", "doing", "tag", "x", "list", List.of("a", "b")))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicTasks.author").value("dana"));

        Map<String, Object> row = row("created-inline");
        assertThat(row.get("author")).isEqualTo("dana");
        assertThat(row.get("status")).isEqualTo("doing");
        assertThat(MAPPER.readTree(String.valueOf(row.get("meta"))))
                .isEqualTo(MAPPER.readTree("{\"tag\":\"x\",\"list\":[\"a\",\"b\"]}"));
    }

    @Test
    void create_unsetVariable_takesTheColumnDefault() throws Exception {
        perform("""
                mutation ($t: String!, $s: String) {
                  createPublicTasks(input: { title: $t, status: $s }) { status }
                }""", vars("t", "created-unset"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicTasks.status").value("todo"));
    }

    @Test
    void createMany_inlineRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($a: String, $b: String, $s: String) {
                  createManyPublicTasks(inputs: [{ title: "many-1", author: $a, status: $s },
                                                 { title: "many-2", author: $b, status: $s }]) { title }
                }""", vars("a", "erin", "b", "fay", "s", "done"))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(row("many-1").get("author")).isEqualTo("erin");
        assertThat(row("many-2").get("author")).isEqualTo("fay");
        assertThat(row("many-2").get("status")).isEqualTo("done");
    }

    @Test
    void updateCollection_setVariablesAndAtMostVariable_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $n: Int) {
                  updatePublicTasksCollection(filter: { title: { eq: $t } }, set: { author: $a }, atMost: $n) { title }
                }""", vars("t", "seed-collection", "a", "gus", "n", 1))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(row("seed-collection").get("author")).isEqualTo("gus");
    }

    @Test
    void upsert_inputVariablesAndUpdateColumnsVariable_overwriteTheExistingRow() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $cols: [String!]) {
                  createPublicTasks(input: { title: $t, author: $a },
                                    onConflict: { constraint: "tasks_title_key", update_columns: $cols }) { author }
                }""", vars("t", "seed-upsert", "a", "hank", "cols", List.of("author")))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicTasks.author").value("hank"));

        assertThat(row("seed-upsert").get("author")).isEqualTo("hank");
    }

    @Test
    void upsert_wholeOnConflictVariable_overwritesTheExistingRow() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $oc: PublicTasksOnConflict) {
                  createPublicTasks(input: { title: $t, author: $a }, onConflict: $oc) { author }
                }""", vars("t", "seed-upsert", "a", "ivy",
                        "oc", Map.of("constraint", "tasks_title_key", "update_columns", List.of("author"))))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicTasks.author").value("ivy"));

        assertThat(row("seed-upsert").get("author")).isEqualTo("ivy");
    }

    @Test
    void nestedInsert_childRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($name: String!, $t: String!, $a: String, $s: String) {
                  createPublicProjects(input: { name: $name,
                    publicTasks: { data: [{ title: $t, author: $a, status: $s }] } }) { id name }
                }""", vars("name", "nested-project", "t", "nested-task", "a", "jack", "s", "doing"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicProjects.name").value("nested-project"));

        Map<String, Object> row = row("nested-task");
        assertThat(row.get("author")).isEqualTo("jack");
        assertThat(row.get("status")).isEqualTo("doing");
    }

    @Test
    void delete_whereVariablesInsideListAndJsonbObject_matchTheirValues() throws Exception {
        perform("""
                mutation ($a: String!, $tag: String!) {
                  deletePublicTasks(where: { author: { in: [$a] }, meta: { contains: { tag: $tag } } }) { title }
                }""", vars("a", "carol", "tag", "gone"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.deletePublicTasks[0].title").value("seed-delete"));

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
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
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
