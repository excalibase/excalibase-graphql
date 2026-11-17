package io.github.excalibase.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.excalibase.metadata.MetadataFixtures.*;

/** A stub document server and the helpers the url source tests share. */
abstract class UrlSourceFixture {

    static final String TOKEN = "s3cret-token-value";

    @TempDir
    Path dir;

    HttpServer server;
    HttpServer elsewhere;
    final AtomicInteger elsewhereHits = new AtomicInteger();
    final List<ReloadingMetadataSource> opened = new ArrayList<>();
    final List<String> authorization = new CopyOnWriteArrayList<>();
    final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();

    final CountDownLatch release = new CountDownLatch(1);
    volatile int status = 200;
    volatile String body = GOOD_YAML;
    volatile String etag;
    volatile long delayMs;

    @BeforeEach
    void startServers() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/permissions.yaml", this::answer);
        server.createContext("/moved", exchange -> redirect(exchange, "/permissions.yaml"));
        server.createContext("/away", exchange -> redirect(exchange, "http://127.0.0.1:"
                + elsewhere.getAddress().getPort() + "/permissions.yaml"));
        server.start();
        elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/", exchange -> {
            elsewhereHits.incrementAndGet();
            reply(exchange, 200, GOOD_YAML, null);
        });
        elsewhere.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        opened.forEach(ReloadingMetadataSource::close);
        server.stop(0);
        elsewhere.stop(0);
    }

    void answer(HttpExchange exchange) throws IOException {
        authorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        ifNoneMatch.add(String.valueOf(exchange.getRequestHeaders().getFirst("If-None-Match")));
        sleep(delayMs);
        String sent = etag;
        if (sent != null && sent.equals(exchange.getRequestHeaders().getFirst("If-None-Match")) && status == 200) {
            reply(exchange, 304, "", sent);
            return;
        }
        reply(exchange, status, body, sent);
    }

    /** Holds a handler for up to {@code millis}, or until the test ends and releases it. */
    void sleep(long millis) {
        try {
            release.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    static void reply(HttpExchange exchange, int code, String text, String entityTag) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        if (entityTag != null) {
            exchange.getResponseHeaders().set("ETag", entityTag);
        }
        exchange.sendResponseHeaders(code, code == 304 ? -1 : payload.length);
        if (code != 304) {
            exchange.getResponseBody().write(payload);
        }
        exchange.close();
    }

    static void redirect(HttpExchange exchange, String to) throws IOException {
        exchange.getResponseHeaders().set("Location", to);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    UrlSettings settings(String path, String token, String tokenFile, Duration readTimeout) {
        return new UrlSettings(url(path), token, tokenFile, false, Duration.ofHours(1), Duration.ofSeconds(2),
                readTimeout);
    }

    ReloadingMetadataSource open(UrlSettings settings) {
        ReloadingMetadataSource source = UrlMetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry());
        opened.add(source);
        source.start();
        return source;
    }

    ReloadingMetadataSource open() {
        return open(settings("/permissions.yaml", "", "", Duration.ofSeconds(5)));
    }

    static void settle(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

}
