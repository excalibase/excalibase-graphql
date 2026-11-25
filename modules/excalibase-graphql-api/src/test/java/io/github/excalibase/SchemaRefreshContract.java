package io.github.excalibase;

import io.github.excalibase.support.EngineIntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A standalone engine has no watcher to say the schema changed, so it notices by itself: DDL run straight on the
 * database shows up in GraphQL within a few poll intervals, with no restart.
 */
abstract class SchemaRefreshContract extends EngineIntegrationTest {

    static final Duration WITHIN = Duration.ofSeconds(20);

    /** The engine settings every database shares: a one second poll, so the test does not wait on the default. */
    static Map<String, String> withFastPoll(Map<String, String> properties) {
        properties.put("app.cache.schema-poll-seconds", "1");
        return properties;
    }

    abstract void execute(String sql);

    private String introspection() {
        return http.graphql("/test-proj/graphql", "{ __schema { types { name fields { name } } } }").body();
    }

    @Test
    @DisplayName("a table created after start is served without a restart")
    void newTableAppears() {
        assertThat(introspection()).doesNotContain("RefreshGadgets");

        execute("CREATE TABLE refresh_gadgets (id INT PRIMARY KEY, label VARCHAR(40))");

        Awaitility.await().atMost(WITHIN).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(introspection()).contains("RefreshGadgets"));
    }

    @Test
    @DisplayName("a column dropped after start leaves the schema without a restart")
    void droppedColumnDisappears() {
        execute("CREATE TABLE refresh_widgets (id INT PRIMARY KEY, doomed_col VARCHAR(40), kept_col VARCHAR(40))");
        Awaitility.await().atMost(WITHIN).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(introspection()).contains("doomed_col"));

        execute("ALTER TABLE refresh_widgets DROP COLUMN doomed_col");

        Awaitility.await().atMost(WITHIN).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(introspection()).doesNotContain("doomed_col").contains("kept_col"));
    }
}
