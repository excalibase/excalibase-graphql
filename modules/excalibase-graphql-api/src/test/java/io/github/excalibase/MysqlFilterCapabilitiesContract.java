package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.EngineProperties;
import io.github.excalibase.support.HttpAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static io.github.excalibase.support.Expect.jsonPath;
import static io.github.excalibase.support.Expect.status;

/**
 * A MySQL schema offers the filters MySQL runs and no others, and the operators it offers run; one it does not offer
 * is an error naming the operator, never a query that answers every row. Run against each MySQL version we support.
 */
abstract class MysqlFilterCapabilitiesContract extends EngineIntegrationTest {

    static MySQLContainer<?> container(String image) {
        return new MySQLContainer<>(image).withInitScript("init-mysql-filters.sql");
    }

    @jakarta.inject.Inject
    io.github.excalibase.schema.GraphqlSchemaManager schemaManager;

    /** The database under test, not yet started. */
    abstract MySQLContainer<?> database();

    @Override
    public Map<String, String> getProperties() {
        MySQLContainer<?> mysql = database();
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

    private HttpAnswer graphql(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        return http.post("/test-proj/graphql", MAPPER.writeValueAsString(body), "Content-Type", "application/json").expect(status().isOk());
    }

    private HttpAnswer graphql(String query) throws Exception {
        return graphql(query, Map.of());
    }

    /** One type per request: graphql-java refuses an introspection that asks for input fields more than once. */
    private HttpAnswer type(String name) throws Exception {
        return graphql("{ __type(name: \"" + name + "\") { name inputFields { name type { name } } } }");
    }

    @Test
    void theSchemaOffersOnlyWhatMysqlRuns() throws Exception {
        type("JsonFilterInput")
                .expect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("eq"))))
                .expect(jsonPath("$.data.__type.inputFields[*].name", hasItem("hasKeys")));
        type("TsvectorFilterInput")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.__type").doesNotExist());
        type("StringFilterInput")
                .expect(jsonPath("$.data.__type.inputFields[*].name", hasItem("iregex")))
                .expect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("search"))));
        type("SearchableStringFilterInput")
                .expect(jsonPath("$.data.__type.inputFields[*].name", hasItem("phraseSearch")))
                .expect(jsonPath("$.data.__type.inputFields[*].name", not(hasItem("webSearch"))));
        type("TestArticleWhereInput")
                .expect(jsonPath("$.data.__type.inputFields[?(@.name == 'body')].type.name")
                        .value(hasItem("SearchableStringFilterInput")))
                .expect(jsonPath("$.data.__type.inputFields[?(@.name == 'title')].type.name")
                        .value(hasItem("StringFilterInput")));
    }

    @Test
    void offeredOperatorsRunOnMysql() throws Exception {
        graphql("{ regex: testArticle(where: { title: { iregex: \"^a|^g\" } }) { id } "
                + "json: testArticle(where: { meta: { hasKeys: [\"a\", \"b\"] } }) { id } "
                + "text: testArticle(where: { body: { search: \"quick fox\" } }) { id } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.regex[*].id", containsInAnyOrder(1, 3)))
                .expect(jsonPath("$.data.json[*].id", containsInAnyOrder(3)))
                .expect(jsonPath("$.data.text[*].id", containsInAnyOrder(1, 3)));
    }

    @Test
    void anOperatorMysqlCannotRunIsAnError() throws Exception {
        graphql("{ testArticle(where: { body: { webSearch: \"fox\" } }) { id } }")
                .expect(jsonPath("$.data").doesNotExist())
                .expect(jsonPath("$.errors[0].message", containsString("'webSearch'")));
        graphql("{ testArticle(where: { title: { search: \"alpha\" } }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("FULLTEXT index")));
    }

    @Test
    void aWholeWhereVariableIsApplied() throws Exception {
        graphql("query($w: TestArticleWhereInput) { testArticle(where: $w) { id } }",
                Map.of("w", Map.of("title", Map.of("eq", "beta"))))
                .expect(jsonPath("$.data.testArticle[*].id", containsInAnyOrder(2)));
    }

    @Test
    void featuresMysqlCannotRunAreAbsentFromTheSchemaAndRefused() throws Exception {
        graphql("{ __type(name: \"TestArticleWhereInput\") { name } }").expect(jsonPath("$.errors").doesNotExist());
        graphql("{ testArticle(distinctOn: [\"title\"]) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("MySQL does not support distinctOn")));
        graphql("{ testArticle(vector: { column: \"title\", near: [1.0, 2.0] }) { id } }")
                .expect(jsonPath("$.errors[0].message", containsString("MySQL does not support the vector argument")));
        graphql("{ testArticle(where: { title: { nonsense: \"x\" } }) { id } }")
                .expect(jsonPath("$.data").doesNotExist())
                .expect(jsonPath("$.errors").exists());
    }

    @Test
    void onConflictUpsertsOnMysql() throws Exception {
        graphql("mutation { createTestArticle(input: { id: 40, title: \"first\" }, onConflict: { constraint: "
                + "\"PRIMARY\", update_columns: [\"title\"] }) { id title } }")
                .expect(jsonPath("$.data.createTestArticle.title").value("first"));
        graphql("mutation { createTestArticle(input: { id: 40, title: \"second\" }, onConflict: { constraint: "
                + "\"PRIMARY\", update_columns: [\"title\"] }) { id title } }")
                .expect(jsonPath("$.data.createTestArticle.title").value("second"));
        graphql("{ testArticle(where: { id: { eq: 40 } }) { title } }")
                .expect(jsonPath("$.data.testArticle", org.hamcrest.Matchers.hasSize(1)));
    }

    /** Two requests upserting the same new key at once both succeed and write one row: a deadlock is retried. */
    @Test
    void concurrentUpsertsOfTheSameNewKeyBothSucceed() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int key = 5000; key < 5030; key++) {
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                int id = key;
                List<java.util.concurrent.Future<?>> requests = new java.util.ArrayList<>();
                for (String title : List.of("left", "right")) {
                    requests.add(pool.submit(() -> {
                        start.await();
                        graphql("mutation { createTestArticle(input: { id: " + id + ", title: \"" + title
                                + "\" }, onConflict: { constraint: \"PRIMARY\", update_columns: [\"title\"] }) "
                                + "{ id title } }")
                                .expect(jsonPath("$.errors").doesNotExist())
                                .expect(jsonPath("$.data.createTestArticle.id").value(id));
                        return null;
                    }));
                }
                for (java.util.concurrent.Future<?> request : requests) {
                    request.get(60, java.util.concurrent.TimeUnit.SECONDS);
                }
                graphql("{ testArticle(where: { id: { eq: " + id + " } }) { id } }")
                        .expect(jsonPath("$.data.testArticle", org.hamcrest.Matchers.hasSize(1)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** REST upserts of the same new key at once both succeed: the REST transaction is retried after a deadlock. */
    @Test
    void concurrentRestMergeDuplicatesOfTheSameNewKeyBothSucceed() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int key = 6000; key < 6030; key++) {
                java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
                int id = key;
                List<java.util.concurrent.Future<?>> requests = new java.util.ArrayList<>();
                for (String title : List.of("left", "right")) {
                    requests.add(pool.submit(() -> {
                        start.await();
                        http.post("/test-proj/api/v1/article", "{\"id\":" + id + ",\"title\":\"" + title + "\"}",
                                "Content-Type", "application/json",
                                "Prefer", "return=representation, resolution=merge-duplicates")
                                .expect(status().is2xxSuccessful());
                        return null;
                    }));
                }
                for (java.util.concurrent.Future<?> request : requests) {
                    request.get(60, java.util.concurrent.TimeUnit.SECONDS);
                }
                graphql("{ testArticle(where: { id: { eq: " + id + " } }) { id } }")
                        .expect(jsonPath("$.data.testArticle", org.hamcrest.Matchers.hasSize(1)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The real MySQL dialect, not a mock: a subscription on MySQL is refused, naming the database. */
    @Test
    void subscriptionsAreRefusedForAMysqlProject() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> schemaManager.realtime(null, "test-proj", null, "testArticle"))
                .isInstanceOf(io.github.excalibase.schema.SubscriptionsUnsupportedException.class)
                .hasMessage("MySQL does not support subscriptions");
    }

    @Test
    void aggregatesRunOnMysql() throws Exception {
        graphql("{ testArticleAggregate { count max { id } } }")
                .expect(jsonPath("$.errors").doesNotExist())
                .expect(jsonPath("$.data.testArticleAggregate.count", org.hamcrest.Matchers.notNullValue()));
    }
}
