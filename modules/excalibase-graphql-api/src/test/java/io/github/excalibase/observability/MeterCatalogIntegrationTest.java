package io.github.excalibase.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The meters of the earlier build, by exposition name and label keys, on a single-database engine. */
class MeterCatalogIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})?\\s+\\S+$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"");

    private static Map<String, Set<String>> shape;

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "micronaut.server.port", "-1",
                "spring.datasource.url", "jdbc:postgresql://localhost:59999/nonexistent",
                "spring.datasource.username", "fake",
                "spring.datasource.password", "fake",
                "app.project-id", "test-proj",
                "app.security.jwt-enabled", "false",
                "app.security.insecure-dev-mode", "true",
                "management.endpoints.web.exposure.include", "health,info,metrics,prometheus",
                "endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
    }

    @BeforeAll
    void scrape() {
        text = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/")).get("/actuator/prometheus").body();
        shape = new TreeMap<>();
        for (String line : text.split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (!line.startsWith("#") && sample.matches()) {
                Set<String> keys = shape.computeIfAbsent(sample.group(1), name -> new TreeSet<>());
                Matcher label = LABEL.matcher(sample.group(2) == null ? "" : sample.group(2));
                while (label.find()) {
                    keys.add(label.group(1));
                }
            }
        }
    }

    private static String text;

    private static void assertSeries(String name, String... labelKeys) {
        assertThat(shape).containsKey(name);
        assertThat(shape.get(name)).containsExactlyInAnyOrderElementsOf(Arrays.asList(labelKeys));
    }

    @Test
    @DisplayName("the application start-up gauges carry the main class")
    void applicationLifecycle() {
        assertSeries("application_started_time_seconds", "main_application_class");
        assertSeries("application_ready_time_seconds", "main_application_class");
    }

    @Test
    @DisplayName("disk, JVM and process meters Spring Boot registered are all there")
    void systemMeters() {
        assertSeries("disk_free_bytes", "path");
        assertSeries("disk_total_bytes", "path");
        assertSeries("jvm_info", "runtime", "vendor", "version");
        assertSeries("jvm_compilation_time_ms_total", "compiler");
        assertSeries("jvm_memory_usage_after_gc", "area", "pool");
        assertSeries("jvm_memory_used_bytes", "area", "id");
        assertSeries("process_uptime_seconds");
        assertSeries("process_start_time_seconds");
        assertSeries("process_files_open_files");
        assertSeries("system_cpu_count");
        assertSeries("logback_events_total", "level");
    }

    @Test
    @DisplayName("the configured database's pool reports through Micrometer under its pool name")
    void hikariMetrics() {
        assertSeries("hikaricp_connections", "pool");
        assertSeries("hikaricp_connections_max", "pool");
        assertSeries("hikaricp_connections_acquire_seconds_count", "pool");
    }

    @Test
    @DisplayName("spring.datasource.hikari.* is ignored: the pool keeps its bounded default size")
    void poolSizeUnchanged() {
        assertThat(text).contains("hikaricp_connections_max{pool=\"HikariPool-1\"} 10.0");
    }

    @Test
    @DisplayName("there is no thread ceiling to configure: -1, as with virtual threads before")
    void noThreadCeiling() {
        assertThat(text).contains("tomcat_threads_config_max_threads{name=\"http-netty\"} -1.0");
    }

    @Test
    @DisplayName("no executor meters: the earlier build had none")
    void noExecutorMeters() {
        Set<String> executors = shape.keySet().stream().filter(name -> name.startsWith("executor_")).collect(Collectors.toSet());
        assertThat(executors).isEmpty();
    }
}
