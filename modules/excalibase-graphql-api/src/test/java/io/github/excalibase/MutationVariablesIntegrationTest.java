package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A variable placed anywhere inside an inline mutation input or filter writes and matches the value it
 * holds, on real Postgres: create, createMany, update, updateCollection, upsert, nested insert and the
 * where filters of update and delete. An input field given an unset variable is left out, as GraphQL says.
 */
class MutationVariablesIntegrationTest extends EngineIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-mutation-variables.sql");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = EngineProperties.datasource(postgres);
        properties.put("app.project-id", "test-proj");
        properties.put("app.security.jwt-enabled", "false");
        properties.put("app.security.insecure-dev-mode", "true");
        return properties;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();


    @Test
    void update_inlineInputVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String, $p: Int) {
                  updatePublicTasks(where: { title: { eq: $t } }, input: { author: $a, status: $s, priority: $p }) {
                    author status priority
                  }
                }""", vars("t", "seed-update", "a", "bob", "s", "doing", "p", 3))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.updatePublicTasks[0].author").value("bob"));

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
                .expect(jsonPath("$.errors").doesNotExist());

        assertThat(MAPPER.readTree(String.valueOf(row("seed-update").get("meta"))))
                .isEqualTo(MAPPER.readTree("{\"tag\":\"hot \\\"quoted\\\"\",\"n\":1}"));
    }

    @Test
    void update_unsetVariable_leavesTheColumnAlone() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $s: String) {
                  updatePublicTasks(where: { title: { eq: $t } }, input: { author: $a, status: $s }) { id }
                }""", vars("t", "seed-unset", "s", "done"))
                .expect(jsonPath("$.errors").doesNotExist());

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
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicTasks.author").value("dana"));

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
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicTasks.status").value("todo"));
    }

    @Test
    void createMany_inlineRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($a: String, $b: String, $s: String) {
                  createManyPublicTasks(inputs: [{ title: "many-1", author: $a, status: $s },
                                                 { title: "many-2", author: $b, status: $s }]) { title }
                }""", vars("a", "erin", "b", "fay", "s", "done"))
                .expect(jsonPath("$.errors").doesNotExist());

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
                .expect(jsonPath("$.errors").doesNotExist());

        assertThat(row("seed-collection").get("author")).isEqualTo("gus");
    }

    @Test
    void upsert_inputVariablesAndUpdateColumnsVariable_overwriteTheExistingRow() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $cols: [String!]) {
                  createPublicTasks(input: { title: $t, author: $a },
                                    onConflict: { constraint: "tasks_title_key", update_columns: $cols }) { author }
                }""", vars("t", "seed-upsert", "a", "hank", "cols", List.of("author")))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicTasks.author").value("hank"));

        assertThat(row("seed-upsert").get("author")).isEqualTo("hank");
    }

    @Test
    void upsert_wholeOnConflictVariable_overwritesTheExistingRow() throws Exception {
        perform("""
                mutation ($t: String!, $a: String, $oc: PublicTasksOnConflict) {
                  createPublicTasks(input: { title: $t, author: $a }, onConflict: $oc) { author }
                }""", vars("t", "seed-upsert", "a", "ivy",
                        "oc", Map.of("constraint", "tasks_title_key", "update_columns", List.of("author"))))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicTasks.author").value("ivy"));

        assertThat(row("seed-upsert").get("author")).isEqualTo("ivy");
    }

    @Test
    void nestedInsert_childRowVariables_storeTheirValues() throws Exception {
        perform("""
                mutation ($name: String!, $t: String!, $a: String, $s: String) {
                  createPublicProjects(input: { name: $name,
                    publicTasks: { data: [{ title: $t, author: $a, status: $s }] } }) { id name }
                }""", vars("name", "nested-project", "t", "nested-task", "a", "jack", "s", "doing"))
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.createPublicProjects.name").value("nested-project"));

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
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.deletePublicTasks[0].title").value("seed-delete"));

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
