package io.github.excalibase;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same requests against the engine at {@code full} and at {@code lite} must leave the same
 * {@code http.server.requests} series behind: every tag value, and the same companion meters.
 */
@Testcontainers
class MonitoringLevelParityIntegrationTest {

    private static final String PROJECT = "test-proj";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("init-rest-test.sql");

    private static final String[][] CALLS = {
        {"POST", "/test-proj/graphql", "{\"query\":\"{ __typename }\"}"},
        {"GET", "/test-proj/api/v1/products", null},
        {"GET", "/test-proj/api/v1/products?price=zz.1", null},
        {"GET", "/test-proj/api/v1/products?order=nosuch.desc", null},
        {"GET", "/unknown-proj/api/v1/products", null},
        {"POST", "/unknown-proj/graphql", "{\"query\":\"{ __typename }\"}"},
        {"GET", "/no/such/route/abc123", null},
        {"DELETE", "/test-proj/graphql", null},
        {"FOOBAR1", "/test-proj/graphql", null},
        {"FOOBAR2", "/test-proj/graphql", null},
        {"GET", "/", null},
    };

    private Map<String, Object> properties(String level, boolean jwt) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", "0");
        properties.put("management.server.port", "0");
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("app.project-id", PROJECT);
        properties.put("app.database-type", "postgres");
        properties.put("app.nats.enabled", "false");
        properties.put("app.security.jwt-enabled", Boolean.toString(jwt));
        properties.put("app.security.insecure-dev-mode", Boolean.toString(!jwt));
        if (jwt) {
            properties.put("app.security.auth.hmac-secret", "0123456789abcdef0123456789abcdef0123456789");
        }
        if (level != null) {
            properties.put("excalibase.observability.level", level);
        }
        return properties;
    }

    /** Command-line arguments, so they outrank application.yaml, as the real settings do. */
    private static String[] arguments(Map<String, Object> properties) {
        return properties.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /** What the engine at this level recorded for {@link #CALLS}: companion meters, then each timer series with its count. */
    private List<String> observe(String level, boolean jwt) throws Exception {
        List<String> seen = new ArrayList<>();
        Set<ObjectName> tomcatBefore = tomcatMBeans();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ExcalibaseGraphqlApplication.class)
                .run(arguments(properties(level, jwt)))) {
            String port = context.getEnvironment().getProperty("local.server.port");
            HttpClient http = HttpClient.newHttpClient();
            for (String[] call : CALLS) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + call[1]));
                if (call[2] != null) {
                    builder.header("Content-Type", "application/json");
                }
                builder.method(call[0], call[2] == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(call[2]));
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                seen.add("CALL " + call[0] + " " + call[1] + " -> " + response.statusCode());
            }
            Thread.sleep(300);
            seen.addAll(series(context.getBean(MeterRegistry.class)));
        } finally {
            unregisterTomcatMBeansAddedSince(tomcatBefore);
        }
        return seen;
    }

    private static Set<ObjectName> tomcatMBeans() throws Exception {
        return ManagementFactory.getPlatformMBeanServer().queryNames(new ObjectName("Tomcat:*"), null);
    }

    /**
     * A closed application leaves its Tomcat MBeans in the JVM-wide MBean server, and the tomcat.* gauges of
     * every later application in this JVM sum over them; remove the ones this test added.
     */
    private static void unregisterTomcatMBeansAddedSince(Set<ObjectName> before) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        for (ObjectName name : tomcatMBeans()) {
            if (!before.contains(name) && server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    private static List<String> series(MeterRegistry registry) {
        TreeSet<String> lines = new TreeSet<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (!name.startsWith("http.server.requests")) {
                continue;
            }
            String tags = meter.getId().getTags().stream().map(tag -> tag.getKey() + "=" + tag.getValue())
                    .sorted().collect(Collectors.joining(","));
            if (meter instanceof Timer timer) {
                lines.add("TIMER " + name + " " + tags + " count=" + timer.count());
            } else {
                lines.add("OTHER " + name + " [" + meter.getId().getType() + "] " + tags);
            }
        }
        return new ArrayList<>(lines);
    }

    @Test
    void liteRecordsTheSameSeriesAsFull() throws Exception {
        List<String> full = observe(null, false);
        List<String> lite = observe("lite", false);

        assertThat(lite).containsExactlyElementsOf(full);
    }

    @Test
    void liteRecordsTheSameSeriesAsFullWhenTheSecurityChainRejects() throws Exception {
        List<String> full = observe(null, true);
        List<String> lite = observe("lite", true);

        assertThat(lite).containsExactlyElementsOf(full);
    }
}
