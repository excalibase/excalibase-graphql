package io.github.excalibase.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** A store that ignores {@code If-Match} must be read-only for the engine, never last-write-wins. */
class S3ConditionalProbeTest {

    private HttpServer store;
    private ReloadingMetadataSource source;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int failingPut;

    @AfterEach
    void stop() {
        if (source != null) {
            source.close();
        }
        store.stop(0);
    }

    /** @param honoursIfMatch whether a PUT with a wrong If-Match is refused with 412 */
    private ReloadingMetadataSource open(boolean honoursIfMatch, boolean refusesAllPuts) throws IOException {
        store = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        store.createContext("/", exchange -> answer(exchange, honoursIfMatch, refusesAllPuts));
        store.start();
        S3Settings settings = S3MetadataSourceTest.settings("http://127.0.0.1:" + store.getAddress().getPort(),
                "meta", "permissions.yaml", MinioFixture.ACCESS_KEY, MinioFixture.SECRET_KEY);
        source = S3MetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false);
        source.start();
        return source;
    }

    private void answer(HttpExchange exchange, boolean honoursIfMatch, boolean refusesAllPuts) throws IOException {
        String method = exchange.getRequestMethod();
        requests.add(method + " " + exchange.getRequestURI().getPath()
                + (exchange.getRequestHeaders().containsKey("If-Match") ? " if-match" : ""));
        exchange.getRequestBody().readAllBytes();
        if ("GET".equals(method) && exchange.getRequestURI().getPath().endsWith("/permissions.yaml")) {
            byte[] body = GOOD_YAML.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("ETag", "\"abc\"");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } else if ("PUT".equals(method) && (refusesAllPuts || failingPut != 0)) {
            exchange.sendResponseHeaders(refusesAllPuts ? 403 : failingPut, -1);
        } else if ("PUT".equals(method) && exchange.getRequestHeaders().containsKey("If-Match") && honoursIfMatch) {
            exchange.sendResponseHeaders(412, -1);
        } else if ("PUT".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(204, -1);
        }
        exchange.close();
    }

    @Test
    void aStoreThatIgnoresIfMatch_isNotWritable_andASaveWritesNothing() throws IOException {
        ReloadingMetadataSource served = open(false, false);
        served.refresh();

        assertThat(served.writable()).isFalse();
        requests.clear();
        assertThat(served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion()))
                .isInstanceOf(SaveOutcome.ReadOnly.class);
        assertThat(requests).noneMatch(request -> request.startsWith("PUT"));
    }

    @Test
    void aStoreThatHonoursIfMatch_isWritable() throws IOException {
        assertThat(open(true, false).writable()).isTrue();
    }

    @Test
    void credentialsThatMayNotWrite_leaveTheSourceReadOnly() throws IOException {
        assertThat(open(true, true).writable()).isFalse();
    }

    @Test
    void anUnreachableStore_isNotWritable() throws IOException {
        ReloadingMetadataSource served = open(true, false);
        store.stop(0);

        assertThat(served.writable()).isFalse();
    }

    @Test
    void aWriteTheStoreFails_isReported_andTheLastGoodDocumentStaysServed() throws IOException {
        ReloadingMetadataSource served = open(true, false);
        served.refresh();
        assertThat(served.writable()).isTrue();
        failingPut = 500;

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Failed.class, failed ->
                assertThat(failed.category()).doesNotContain("abc"));
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
    }
}
