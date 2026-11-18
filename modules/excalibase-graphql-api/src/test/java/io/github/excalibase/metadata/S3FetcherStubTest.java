package io.github.excalibase.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the fetcher sends and refuses, seen from a stub store that records requests. */
class S3FetcherStubTest {

    private static final String BUCKET = "private-bucket-name";
    private static final String KEY = "private/key-name.yaml";

    @TempDir
    Path dir;

    private HttpServer store;
    private HttpServer elsewhere;
    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String location = "";
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void start() throws IOException {
        store = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        store.createContext("/", this::answer);
        store.start();
        elsewhere = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        elsewhere.createContext("/", exchange -> {
            elsewhereHits.incrementAndGet();
            reply(exchange, 200, GOOD_YAML);
        });
        elsewhere.start();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        store.stop(0);
        elsewhere.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if (status == 307) {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
            return;
        }
        reply(exchange, status, GOOD_YAML);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private S3Settings settings(String endpoint, S3Settings.Credentials credentials) {
        return new S3Settings(endpoint, BUCKET, KEY, "us-east-1", null, true, credentials, Duration.ofHours(1),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    private S3DocumentFetcher fetcher(S3Settings settings) {
        return new S3DocumentFetcher(S3Location.require(settings, PROJECT), settings);
    }

    private String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aRedirect_isNotFollowed_soTheSignatureNeverLeavesTheConfiguredEndpoint() {
        status = 307;
        location = endpoint(elsewhere) + "/" + BUCKET + "/" + KEY;
        S3DocumentFetcher fetcher = fetcher(settings(endpoint(store), S3Settings.Credentials.literal("id", "secret")));

        assertThatThrownBy(fetcher::load).isInstanceOf(IOException.class).hasMessageContaining("307")
                .hasMessageContaining("not followed");
        assertThat(elsewhereHits).hasValue(0);
    }

    @Test
    void aCredentialsFileRotatedMidRun_isUsedOnTheNextRead() throws Exception {
        Path id = Files.writeString(dir.resolve("id"), "first-id\n");
        Path secret = Files.writeString(dir.resolve("secret"), "first-secret\n");
        S3DocumentFetcher fetcher = fetcher(settings(endpoint(store),
                new S3Settings.Credentials("", "", "", id.toString(), secret.toString(), "")));

        fetcher.load();
        Files.writeString(id, "second-id\n");
        Files.writeString(secret, "second-secret\n");
        fetcher.load();

        assertThat(authorization).hasSize(2);
        assertThat(authorization.get(0)).contains("Credential=first-id/");
        assertThat(authorization.get(1)).contains("Credential=second-id/");
    }

    @Test
    void aTransportError_namesNeitherTheEndpointNorTheBucketNorTheKey() {
        S3Settings unreachable = settings("http://127.0.0.1:1", S3Settings.Credentials.literal("id", "secret"));
        ReloadingMetadataSource source = S3MetadataSource.create(unreachable, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false);
        try {
            source.start();
        } finally {
            source.close();
        }
        String logged = logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));

        assertThat(logged).contains("metadata_refused source=s3").doesNotContain("127.0.0.1")
                .doesNotContain(BUCKET).doesNotContain("key-name");
        assertThatThrownBy(() -> fetcher(unreachable).load()).isInstanceOf(IOException.class)
                .hasMessageNotContaining("127.0.0.1").hasMessageNotContaining(BUCKET).hasMessageNotContaining("key-name");
    }
}
