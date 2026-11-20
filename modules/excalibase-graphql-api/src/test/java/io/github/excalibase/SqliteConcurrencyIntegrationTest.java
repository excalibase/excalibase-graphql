package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many writers on one file: every write lands exactly once, none answers SQLITE_BUSY, and none waits for ever.
 * The file is in WAL mode, with a busy timeout and a write lock taken when each transaction begins.
 */
class SqliteConcurrencyIntegrationTest extends EngineIntegrationTest {

    private static final int WRITERS = 8;
    private static final int WRITES_EACH = 15;
    private static final SqliteFile DATABASE = SqliteFile.create(
            "CREATE TABLE counters (id INTEGER PRIMARY KEY, writer INTEGER NOT NULL, n INTEGER NOT NULL);\n"
                    + "CREATE TABLE totals (id INTEGER PRIMARY KEY, value INTEGER NOT NULL);\n"
                    + "INSERT INTO totals VALUES (1, 0);");

    @Override
    public Map<String, String> getProperties() {
        return DATABASE.engine("busy");
    }

    @Test
    void concurrentWriters_neverCorruptNorLockForever() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        List<Future<Integer>> failures = new ArrayList<>();
        for (int writer = 0; writer < WRITERS; writer++) {
            int id = writer;
            failures.add(pool.submit(() -> writeAll(id)));
        }
        int failed = 0;
        for (Future<Integer> future : failures) {
            failed += future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        JdbcTemplate database = new JdbcTemplate(new DriverManagerDataSource(DATABASE.url()));
        assertThat(failed).as("writes that failed").isZero();
        assertThat(database.queryForObject("SELECT count(*) FROM counters WHERE writer < 90", Integer.class))
                .isEqualTo(WRITERS * WRITES_EACH);
        assertThat(database.queryForObject("SELECT count(DISTINCT writer || ':' || n) FROM counters WHERE writer < 90", Integer.class))
                .isEqualTo(WRITERS * WRITES_EACH);
        assertThat(database.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(database.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
    }

    @Test
    void readsKeepAnswering_whileAWriterHoldsTheLock() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> writing = pool.submit(() -> writeAll(99));
        for (int i = 0; i < 20; i++) {
            HttpAnswer read = http.graphql("/busy/graphql", "{ mainTotals { value } }");
            assertThat(read.status()).as(read.body()).isEqualTo(200);
            assertThat(read.json().path("errors").isMissingNode()).as(read.body()).isTrue();
        }
        assertThat(writing.get(120, TimeUnit.SECONDS)).isZero();
        pool.shutdown();
    }

    private int writeAll(int writer) {
        int failed = 0;
        for (int n = 0; n < WRITES_EACH; n++) {
            HttpAnswer answer = http.graphql("/busy/graphql", "mutation { createMainCounters(input: { writer: " + writer
                    + ", n: " + n + " }) { id } }");
            if (answer.status() != 200 || answer.json().has("errors")) {
                failed++;
            }
        }
        return failed;
    }
}
