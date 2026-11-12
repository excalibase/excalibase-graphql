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

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A MySQL schema offers the filters MySQL runs and no others, and the operators it offers run against MySQL 8.4;
 * one it does not offer is an error naming the operator, never a query that answers every row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MysqlFilterCapabilitiesIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withInitScript("init-mysql-filters.sql");

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

    private ResultActions graphql(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        return mockMvc.perform(post("/test-proj/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andExpect(status().isOk());
    }

    private ResultActions graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    /** One type per request: graphql-java refuses an introspection that asks for input fields more than once. */
    private ResultActions type(String name) throws Exception {
        return graphql("{ __type(name: \"" + name + "\") { name inputFields { name type { name } } } }");
    }

    @Test
    void theSchemaOffersOnlyWhatMysqlRuns() throws Exception {
        type("JsonFilterInput")
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("eq"))))
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", hasItem("hasKeys")));
        type("TsvectorFilterInput")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.__type").doesNotExist());
        type("StringFilterInput")
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", hasItem("iregex")))
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("search"))));
        type("SearchableStringFilterInput")
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", hasItem("phraseSearch")))
                .andExpect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("webSearch"))));
        type("TestArticleWhereInput")
                .andExpect(jsonPath("$.data.__type.inputFields[?(@.name == 'body')].type.name")
                        .value(hasItem("SearchableStringFilterInput")))
                .andExpect(jsonPath("$.data.__type.inputFields[?(@.name == 'title')].type.name")
                        .value(hasItem("StringFilterInput")));
    }

    @Test
    void offeredOperatorsRunOnMysql() throws Exception {
        graphql("{ regex: testArticle(where: { title: { iregex: \"^a|^g\" } }) { id } "
                + "json: testArticle(where: { meta: { hasKeys: [\"a\", \"b\"] } }) { id } "
                + "text: testArticle(where: { body: { search: \"quick fox\" } }) { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.regex[*].id", containsInAnyOrder(1, 3)))
                .andExpect(jsonPath("$.data.json[*].id", containsInAnyOrder(3)))
                .andExpect(jsonPath("$.data.text[*].id", containsInAnyOrder(1, 3)));
    }

    @Test
    void anOperatorMysqlCannotRunIsAnError() throws Exception {
        graphql("{ testArticle(where: { body: { webSearch: \"fox\" } }) { id } }")
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].message", containsString("'webSearch'")));
        graphql("{ testArticle(where: { title: { search: \"alpha\" } }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("FULLTEXT index")));
    }

    @Test
    void aWholeWhereVariableIsApplied() throws Exception {
        graphql("query($w: TestArticleWhereInput) { testArticle(where: $w) { id } }",
                Map.of("w", Map.of("title", Map.of("eq", "beta"))))
                .andExpect(jsonPath("$.data.testArticle[*].id", containsInAnyOrder(2)));
    }

    @Test
    void onConflictIsRefusedOnMysql() throws Exception {
        graphql("mutation { createTestArticle(input: { title: \"x\" }, onConflict: { constraint: \"PRIMARY\", "
                + "update_columns: [\"title\"] }) { id } }")
                .andExpect(jsonPath("$.errors[0].message", containsString("'onConflict'")));
    }
}
