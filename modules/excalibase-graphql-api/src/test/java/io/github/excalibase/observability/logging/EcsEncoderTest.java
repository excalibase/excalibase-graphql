package io.github.excalibase.observability.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The line has the field names, nesting and order of Spring Boot's ECS structured logging, which collectors already parse. */
class EcsEncoderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final LoggerContext context = new LoggerContext();
    private EcsEncoder encoder;

    @BeforeEach
    void start() {
        encoder = new EcsEncoder();
        encoder.setServiceName("Excalibase graphql");
        encoder.setServiceVersion("0.0.1-SNAPSHOT");
        context.setMDCAdapter(new LogbackMDCAdapter());
        encoder.setContext(context);
        encoder.start();
    }

    private LoggingEvent event(String message, Throwable thrown) {
        LoggingEvent event = new LoggingEvent("io.github.excalibase.Demo", context.getLogger("io.github.excalibase.Demo"),
                Level.WARN, message, thrown, null);
        event.setInstant(Instant.parse("2026-11-12T20:52:42.242736672Z"));
        event.setThreadName("main");
        return event;
    }

    private JsonNode line(LoggingEvent event) throws Exception {
        String text = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(text).endsWith("\n").doesNotContain("\n{");
        return JSON.readTree(text);
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("an ordinary line carries the same fields, in the same order, as the Spring Boot ECS line")
    void ordinaryLine() throws Exception {
        JsonNode line = line(event("hello", null));

        assertThat(names(line)).containsExactly("@timestamp", "log", "process", "service", "message", "ecs");
        assertThat(line.get("@timestamp").asText()).isEqualTo("2026-11-12T20:52:42.242736672Z");
        assertThat(line.get("log").toString()).isEqualTo("{\"level\":\"WARN\",\"logger\":\"io.github.excalibase.Demo\"}");
        assertThat(line.get("process").get("thread").get("name").asText()).isEqualTo("main");
        assertThat(line.get("process").get("pid").isNumber()).isTrue();
        assertThat(line.get("service").toString())
                .isEqualTo("{\"name\":\"Excalibase graphql\",\"version\":\"0.0.1-SNAPSHOT\",\"node\":{}}");
        assertThat(line.get("message").asText()).isEqualTo("hello");
        assertThat(line.get("ecs").toString()).isEqualTo("{\"version\":\"8.11\"}");
    }

    @Test
    @DisplayName("a thrown error is named by type, message and stack trace, before the ecs version")
    void errorLine() throws Exception {
        JsonNode line = line(event("failed", new IllegalStateException("boom")));

        assertThat(names(line)).containsExactly("@timestamp", "log", "process", "service", "message", "error", "ecs");
        JsonNode error = line.get("error");
        assertThat(names(error)).containsExactly("type", "message", "stack_trace");
        assertThat(error.get("type").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(error.get("message").asText()).isEqualTo("boom");
        assertThat(error.get("stack_trace").asText()).startsWith("java.lang.IllegalStateException: boom\n\tat ");
    }

    @Test
    @DisplayName("mapped diagnostic context entries are top-level fields, after the message")
    void mdcLine() throws Exception {
        LoggingEvent event = event("traced", null);
        event.setMDCPropertyMap(Map.of("traceId", "abc"));

        JsonNode line = line(event);

        assertThat(names(line)).containsExactly("@timestamp", "log", "process", "service", "message", "traceId", "ecs");
        assertThat(line.get("traceId").asText()).isEqualTo("abc");
    }

    @Test
    @DisplayName("the message is escaped, so one event is always one line")
    void escaping() throws Exception {
        JsonNode line = line(event("two\nlines \"quoted\"", null));

        assertThat(line.get("message").asText()).isEqualTo("two\nlines \"quoted\"");
    }
}
