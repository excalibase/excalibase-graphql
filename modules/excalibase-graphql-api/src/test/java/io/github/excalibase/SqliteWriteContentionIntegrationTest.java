package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every kind of write, over GraphQL and REST, from many threads at once: each must succeed. A write that reads before
 * it takes the write lock cannot upgrade and answers 500 at once, so this fails if the lock is not taken first
 * ({@code SqlDialect.beforeWrite}).
 */
class SqliteWriteContentionIntegrationTest extends EngineIntegrationTest {

    private static final int THREADS = 16;
    private static final int REQUESTS_EACH = 20;
    private static final String JSON = "Content-Type";
    private static final SqliteFile DATABASE = SqliteFile.create("""
            CREATE TABLE items (id INTEGER PRIMARY KEY, k TEXT UNIQUE, v INTEGER NOT NULL DEFAULT 0);
            INSERT INTO items (id, k, v) VALUES (1, 'one', 0), (2, 'two', 0), (3, 'three', 0);
            """);

    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());

    @Override
    public Map<String, String> getProperties() {
        return DATABASE.engine("contend");
    }

    private HttpAnswer graphql(String query) {
        return http.graphql("/contend/graphql", query);
    }

    private HttpAnswer rest(String method, String path, String body) {
        return http.send(method, "/contend/api/v1/" + path, body, "Prefer", "return=representation, resolution=merge-duplicates",
                JSON, "application/json");
    }

    private boolean succeeded(HttpAnswer answer) {
        boolean good = answer.status() < 300 && !answer.body().contains("\"errors\"");
        if (!good) {
            failures.add(answer.status() + " " + answer.body());
        }
        return good;
    }

    @Test
    void everyWritePath_succeedsUnderContention() throws Exception {
        List<IntFunction<HttpAnswer>> paths = List.of(
                n -> graphql("mutation { updateMainItems(where: { id: { eq: 1 } }, input: { v: " + n + " }) { id } }"),
                n -> rest("PATCH", "items?id=eq.2", "{\"v\":" + n + "}"),
                n -> graphql("mutation { createMainItems(input: { id: 3, k: \"three\", v: " + n + " },"
                        + " onConflict: { constraint: \"PRIMARY\", update_columns: [v] }) { id } }"),
                n -> rest("POST", "items", "{\"id\":3,\"k\":\"three\",\"v\":" + n + "}"),
                n -> graphql("mutation { createManyMainItems(inputs: [{ k: \"a" + n + "t" + Thread.currentThread().threadId()
                        + "\" }, { k: \"b" + n + "t" + Thread.currentThread().threadId() + "\" }]) { id } }"),
                n -> graphql("mutation { deleteMainItems(where: { k: { like: \"a" + n + "t%\" } }) { id } }"),
                n -> rest("DELETE", "items?k=like.b" + n + "t*", null),
                n -> rest("POST", "items", "{\"k\":\"r" + n + "t" + Thread.currentThread().threadId() + "\"}"),
                n -> graphql("{ mainItems(where: { id: { lte: 3 } }) { id v } }"));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Integer>> outcomes = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            int offset = thread;
            outcomes.add(pool.submit(() -> {
                int failed = 0;
                for (int n = 0; n < REQUESTS_EACH; n++) {
                    failed += succeeded(paths.get((offset + n) % paths.size()).apply(n)) ? 0 : 1;
                }
                return failed;
            }));
        }
        int failed = 0;
        for (Future<Integer> outcome : outcomes) {
            failed += outcome.get(300, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failed).as("failed requests, first: " + failures.stream().limit(3).toList()).isZero();
        JdbcTemplate database = new JdbcTemplate(new DriverManagerDataSource(DATABASE.url()));
        assertThat(database.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
    }
}
