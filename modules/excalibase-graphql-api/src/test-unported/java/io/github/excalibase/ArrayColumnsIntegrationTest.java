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

import java.net.URI;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postgres array columns on real Postgres: GraphQL types them as lists, JSON arrays write through GraphQL
 * (literal, variable, whole-input variable) and REST, and contains / containedBy / overlaps filter on both
 * (PostgREST cs / cd / ov). A value the column cannot hold is a 400 naming the problem, never an internal error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArrayColumnsIntegrationTest {

    private static final String GRAPHQL = "/test-proj/graphql";
    private static final String REST = "/test-proj/api/v1/tasks";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-array-columns.sql");

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

    // === Schema ===

    @Test
    void schema_typesArrayColumnsAsLists_inOutputInputAndFilters() throws Exception {
        graphql("{ __type(name: \"PublicTasks\") { fields { name type { kind ofType { name } } } } }", Map.of())
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'labels')].type.kind").value("LIST"))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'labels')].type.ofType.name").value("String"))
                .andExpect(jsonPath("$.data.__type.fields[?(@.name == 'scores')].type.ofType.name").value("Int"));
        graphql("{ __type(name: \"PublicTasksCreateInput\") { inputFields { name type { kind ofType { name } } } } }",
                Map.of())
                .andExpect(jsonPath("$.data.__type.inputFields[?(@.name == 'labels')].type.kind").value("LIST"));
        graphql("{ __type(name: \"PublicTasksWhereInput\") { inputFields { name type { name } } } }", Map.of())
                .andExpect(jsonPath("$.data.__type.inputFields[?(@.name == 'labels')].type.name")
                        .value("StringArrayFilterInput"));
        graphql("""
                { __type(name: "StringArrayFilterInput") { inputFields { name type { kind ofType { name } } } } }""",
                Map.of())
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", containsInAnyOrder("eq", "neq", "contains",
                        "containedBy", "overlaps", "arrayContains", "arrayHasAll", "arrayHasAny", "isNull",
                        "isNotNull")))
                .andExpect(jsonPath("$.data.__type.inputFields[?(@.name == 'contains')].type.kind").value("LIST"));
    }

    // === GraphQL writes ===

    @Test
    void graphqlCreate_listLiteral_storesTheArray() throws Exception {
        graphql("""
                mutation { createPublicTasks(input: { title: "gql-literal", labels: ["bug", "a \\"q\\", b"], scores: [7, 8] })
                  { labels scores } }""", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPublicTasks.labels[1]").value("a \"q\", b"))
                .andExpect(jsonPath("$.data.createPublicTasks.scores[0]").value(7));
        assertThat(labels("gql-literal")).containsExactly("bug", "a \"q\", b");
    }

    @Test
    void graphqlCreate_listVariables_inlineAndWholeInput_storeTheArray() throws Exception {
        graphql("mutation ($l: [String]) { createPublicTasks(input: { title: \"gql-var\", labels: $l }) { id } }",
                Map.of("l", List.of("x", "y")))
                .andExpect(jsonPath("$.errors").doesNotExist());
        graphql("mutation ($in: PublicTasksCreateInput!) { createPublicTasks(input: $in) { id } }",
                Map.of("in", Map.of("title", "gql-whole", "labels", List.of("z"), "scores", List.of(1))))
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(labels("gql-var")).containsExactly("x", "y");
        assertThat(labels("gql-whole")).containsExactly("z");
    }

    @Test
    void graphqlCreate_postgresArrayLiteralString_stillStoresTheArray() throws Exception {
        graphql("mutation { createPublicTasks(input: { title: \"gql-pg-literal\", labels: \"{p,q}\" }) { id } }",
                Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(labels("gql-pg-literal")).containsExactly("p", "q");
    }

    @Test
    void graphqlUpdate_list_replacesTheArray() throws Exception {
        graphql("""
                mutation ($l: [String]) { updatePublicTasks(where: { title: { eq: "seed-update" } }, input: { labels: $l })
                  { labels } }""", Map.of("l", List.of("new", "newer")))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(labels("seed-update")).containsExactly("new", "newer");
    }

    @Test
    void graphqlCreate_valueTheElementTypeCannotHold_isInvalidValue_notInternal() throws Exception {
        graphql("mutation { createPublicTasks(input: { title: \"gql-bad\", scores: [\"x\"] }) { id } }", Map.of())
                .andExpect(jsonPath("$.errors[0].extensions.code").value("invalid_value"));
    }

    // === GraphQL filters ===

    @Test
    void graphqlFilters_containsContainedByOverlaps_matchByArraySemantics() throws Exception {
        assertTitles("{ labels: { contains: [\"bug\"] } }", "seed-bug-ui", "seed-bug");
        assertTitles("{ labels: { contains: [\"bug\", \"ui\"] } }", "seed-bug-ui");
        assertTitles("{ labels: { containedBy: [\"bug\", \"docs\"] } }", "seed-bug", "seed-docs");
        assertTitles("{ labels: { overlaps: [\"ui\", \"docs\"] } }", "seed-bug-ui", "seed-docs");
        assertTitles("{ labels: { eq: [\"bug\"] } }", "seed-bug");
        assertTitles("{ scores: { contains: [3] } }", "seed-bug");
    }

    @Test
    void graphqlFilters_sdkOperatorNames_matchTheirArrayOperators() throws Exception {
        assertTitles("{ labels: { arrayContains: [\"bug\"] } }", "seed-bug-ui", "seed-bug");
        assertTitles("{ labels: { arrayHasAll: [\"bug\", \"ui\"] } }", "seed-bug-ui");
        assertTitles("{ labels: { arrayHasAny: [\"docs\"] } }", "seed-docs");
    }

    @Test
    void graphqlFilter_listVariable_matches() throws Exception {
        graphql("query ($l: [String]) { publicTasks(where: { title: { startsWith: \"seed\" }, labels: { contains: $l } }) { title } }",
                Map.of("l", List.of("ui")))
                .andExpect(jsonPath("$.data.publicTasks[*].title", containsInAnyOrder("seed-bug-ui")));
    }

    // === REST ===

    @Test
    void restPost_jsonArray_storesTheArray() throws Exception {
        mockMvc.perform(post(REST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"rest-post\",\"labels\":[\"bug\",\"ui\"],\"scores\":[4]}"))
                .andExpect(status().isCreated());
        assertThat(labels("rest-post")).containsExactly("bug", "ui");
    }

    @Test
    void restPatch_jsonArray_replacesTheArray() throws Exception {
        mockMvc.perform(patch(REST + "?title=eq.seed-rest-patch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"patched\"]}"))
                .andExpect(status().is2xxSuccessful());
        assertThat(labels("seed-rest-patch")).containsExactly("patched");
    }

    @Test
    void restFilters_csCdOv_matchByArraySemantics() throws Exception {
        assertRestTitles("labels=cs.{bug}", "seed-bug-ui", "seed-bug");
        assertRestTitles("labels=cd.{bug,docs}", "seed-bug", "seed-docs");
        assertRestTitles("labels=ov.{ui,docs}", "seed-bug-ui", "seed-docs");
        assertRestTitles("scores=cs.{3}", "seed-bug");
    }

    @Test
    void restPost_valueTheElementTypeCannotHold_is400() throws Exception {
        mockMvc.perform(post(REST).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"rest-bad\",\"scores\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_value"));
    }

    // === Helpers ===

    private void assertTitles(String where, String... titles) throws Exception {
        String seeded = "{ title: { startsWith: \"seed\" }, " + where.substring(1);
        graphql("{ publicTasks(where: " + seeded + ", orderBy: { id: ASC }) { title } }", Map.of())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.publicTasks[*].title", containsInAnyOrder((Object[]) titles)));
    }

    private void assertRestTitles(String query, String... titles) throws Exception {
        String encoded = query.replace("{", "%7B").replace("}", "%7D");
        mockMvc.perform(get(URI.create(REST + "?select=title&title=like.seed*&" + encoded)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].title", containsInAnyOrder((Object[]) titles)));
    }

    private ResultActions graphql(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", new LinkedHashMap<>(variables));
        return mockMvc.perform(post(GRAPHQL).contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private static List<String> labels(String title) throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
             PreparedStatement statement = connection.prepareStatement("SELECT labels FROM tasks WHERE title = ?")) {
            statement.setString(1, title);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("row " + title).isTrue();
                Array array = result.getArray(1);
                return array == null ? null : new ArrayList<>(Arrays.asList((String[]) array.getArray()));
            }
        }
    }

}
