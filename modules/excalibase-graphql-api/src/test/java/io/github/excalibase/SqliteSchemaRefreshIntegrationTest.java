package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.HttpAnswer;
import io.github.excalibase.support.SqliteFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A standalone engine on a file sees a table another process creates after it started, as it does on Postgres and MySQL. */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SqliteSchemaRefreshIntegrationTest extends EngineIntegrationTest {

    private static final SqliteFile DATABASE = SqliteFile.create("CREATE TABLE early (id INTEGER PRIMARY KEY);");

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = DATABASE.engine("refresh");
        properties.put("app.cache.schema-poll-seconds", "1");
        return properties;
    }

    @Test
    void aTableCreatedAfterStart_isServedWithoutARestart() {
        assertThat(http.graphql("/refresh/graphql", "{ mainLate { id } }").json().has("errors")).isTrue();

        DATABASE.run("""
                CREATE TABLE late (id INTEGER PRIMARY KEY, name TEXT);
                INSERT INTO late VALUES (1, 'now');
                """);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            HttpAnswer answer = http.graphql("/refresh/graphql", "{ mainLate { id name } }");
            assertThat(answer.body()).contains("\"name\":\"now\"");
        });
    }
}
