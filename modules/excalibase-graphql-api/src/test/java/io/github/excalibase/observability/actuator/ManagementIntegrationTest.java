package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The management port with probes on and every exposed endpoint, in multi-tenant mode where no database is configured. */
class ManagementIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final String SPRING_JSON = "application/vnd.spring-boot.actuator.v3+json";

    private final EngineClient management = new EngineClient(URI.create("http://localhost:" + MANAGEMENT_PORT + "/"));

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "micronaut.server.port", "-1",
                "app.security.multi-tenant.provisioning-url", "http://localhost:59998/api",
                "app.security.auth.hmac-secret", "management-test-hmac-secret-0123456789abcdef",
                "management.endpoints.web.exposure.include", "health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled", "true",
                "endpoints.all.port", Integer.toString(MANAGEMENT_PORT));
    }

    private static List<String> linkNames(HttpAnswer index) {
        List<String> names = new ArrayList<>();
        index.json().path("_links").fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    @Test
    @DisplayName("the index lists the exposed endpoints with Spring's link names")
    void index() {
        HttpAnswer answer = management.get("/actuator");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).isEqualTo(SPRING_JSON);
        assertThat(linkNames(answer)).containsExactly("health", "health-path", "info", "metrics",
                "metrics-requiredMetricName", "prometheus", "self");
        JsonNode health = answer.json().path("_links").path("health");
        assertThat(health.path("href").asText()).endsWith("/actuator/health");
        assertThat(health.path("templated").asBoolean()).isFalse();
        assertThat(answer.json().path("_links").path("health-path").path("templated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("health is UP with the probe groups listed")
    void health() {
        HttpAnswer answer = management.get("/actuator/health");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).isEqualTo(SPRING_JSON);
        assertThat(answer.json().path("status").asText()).isEqualTo("UP");
        assertThat(answer.json().path("groups")).extracting(JsonNode::asText).containsExactly("liveness", "readiness");
        assertThat(answer.json().fieldNames()).toIterable().containsExactlyInAnyOrder("status", "groups");
    }

    @Test
    @DisplayName("liveness and readiness are UP when probes are enabled; an unknown group is a bare 404")
    void probes() {
        assertThat(management.get("/actuator/health/liveness").body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(management.get("/actuator/health/readiness").body()).isEqualTo("{\"status\":\"UP\"}");
        HttpAnswer unknown = management.get("/actuator/health/nothing");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).isEmpty();
    }

    @Test
    @DisplayName("info is an empty object")
    void info() {
        HttpAnswer answer = management.get("/actuator/info");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).isEqualTo(SPRING_JSON);
        assertThat(answer.body()).isEqualTo("{}");
    }

    @Test
    @DisplayName("metrics lists names sorted, and one metric has measurements and tags")
    void metrics() {
        HttpAnswer names = management.get("/actuator/metrics");
        assertThat(names.status()).isEqualTo(200);
        assertThat(names.header("Content-Type")).isEqualTo(SPRING_JSON);
        List<String> listed = new ArrayList<>();
        names.json().path("names").forEach(name -> listed.add(name.asText()));
        assertThat(listed).contains("jvm.memory.used").isSorted();

        HttpAnswer one = management.get("/actuator/metrics/jvm.memory.used");
        assertThat(one.json().path("name").asText()).isEqualTo("jvm.memory.used");
        assertThat(one.json().path("measurements").path(0).path("statistic").asText()).isEqualTo("VALUE");
        assertThat(one.json().path("availableTags").path(0).path("tag").asText()).isNotEmpty();

        HttpAnswer filtered = management.get("/actuator/metrics/jvm.memory.used?tag=area:heap");
        assertThat(filtered.status()).isEqualTo(200);
        assertThat(management.get("/actuator/metrics/no.such.metric").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("prometheus answers the text exposition format")
    void prometheus() {
        HttpAnswer answer = management.get("/actuator/prometheus");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).startsWith("text/plain").contains("version=0.0.4");
        assertThat(answer.body()).contains("jvm_memory_used_bytes{");
    }

    @Test
    @DisplayName("endpoints Spring never exposed here are not served")
    void notExposed() {
        assertThat(management.get("/actuator/env").status()).isEqualTo(404);
        assertThat(management.get("/actuator/beans").status()).isEqualTo(404);
        assertThat(management.get("/actuator/loggers").status()).isEqualTo(404);
        assertThat(management.get("/actuator/routes").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the public port serves no actuator")
    void publicPortHasNone() {
        assertThat(http.get("/actuator/health").status()).isEqualTo(404);
        assertThat(http.get("/actuator").status()).isEqualTo(404);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {1}")
    @org.junit.jupiter.params.provider.CsvSource({"GET,/p/graphql", "POST,/p/graphql", "GET,/p/api/v1/customer",
            "GET,/p/api/v1/realtime", "GET,/graphql"})
    @DisplayName("the management port serves the actuator only; a project route is a 404 there")
    void projectRoutesAreNotServed(String method, String path) {
        assertThat(management.raw(method, path).status()).isEqualTo(404);
    }
}
