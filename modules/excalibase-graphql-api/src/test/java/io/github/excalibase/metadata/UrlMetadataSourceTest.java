package io.github.excalibase.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlMetadataSourceTest {

    private static final String TOKEN = "s3cret-token-value";

    @TempDir
    Path dir;

    private HttpServer server;
    private HttpServer elsewhere;
    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final List<ReloadingMetadataSource> opened = new ArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private final List<String> ifNoneMatch = new CopyOnWriteArrayList<>();

    private final CountDownLatch release = new CountDownLatch(1);
    private volatile int status = 200;
    private volatile String body = GOOD_YAML;
    private volatile String etag;
    private volatile long delayMs;

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

    private void answer(HttpExchange exchange) throws IOException {
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
    private void sleep(long millis) {
        try {
            release.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reply(HttpExchange exchange, int code, String text, String entityTag) throws IOException {
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

    private static void redirect(HttpExchange exchange, String to) throws IOException {
        exchange.getResponseHeaders().set("Location", to);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private UrlSettings settings(String path, String token, String tokenFile, Duration readTimeout) {
        return new UrlSettings(url(path), token, tokenFile, false, Duration.ofHours(1), Duration.ofSeconds(2),
                readTimeout);
    }

    private ReloadingMetadataSource open(UrlSettings settings) {
        ReloadingMetadataSource source = UrlMetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry());
        opened.add(source);
        source.start();
        return source;
    }

    private ReloadingMetadataSource open() {
        return open(settings("/permissions.yaml", "", "", Duration.ofSeconds(5)));
    }

    private static void settle(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

    @Test
    void a200_isServed_andIsNamedAfterTheSource() {
        ReloadingMetadataSource source = open();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.name()).isEqualTo("url");
        assertThat(source.writable()).isFalse();
        assertThat(source.status().sourceVersion()).startsWith("sha256:");
    }

    @Test
    void theVersionIsTheBodyHash_andNoPollAsksForANotModified() {
        etag = "\"v1\"";
        ReloadingMetadataSource source = open();

        settle(source);

        assertThat(ifNoneMatch).containsOnly("null");
        assertThat(source.status().sourceVersion()).startsWith("sha256:");
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aSameEtagWithAChangedBody_isStillApplied() {
        etag = "\"same\"";
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aNewEtagOnEveryPoll_forTheSameBody_doesNotStarveTheSource() {
        AtomicInteger counter = new AtomicInteger();
        server.createContext("/churn", exchange -> reply(exchange, 200, GOOD_YAML, "\"e" + counter.incrementAndGet() + "\""));

        ReloadingMetadataSource source = open(settings("/churn", "", "", Duration.ofSeconds(5)));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aTruncatedBodyFollowedByA304_isNotSettled() {
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        etag = "\"t\"";
        source.refresh();
        status = 304;
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void aNotModifiedNobodyAskedFor_isRefused_andServesNothingBeforeTheFirstLoad() {
        status = 304;

        ReloadingMetadataSource source = open();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aNewEtag_isApplied_afterTheTwoReadsRule() {
        etag = "\"v1\"";
        ReloadingMetadataSource source = open();

        body = GOOD_YAML_TWO_RULES;
        etag = "\"v2\"";
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.status().version()).isEqualTo(2);
    }

    @Test
    void withoutAnEtag_aChangedBodyIsApplied_andAnUnchangedBodyIsNot() {
        ReloadingMetadataSource source = open();
        settle(source);
        assertThat(source.status().version()).isEqualTo(1);

        body = GOOD_YAML_TWO_RULES;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(source.status().version()).isEqualTo(2);
        assertThat(ifNoneMatch).containsOnly("null");
    }

    @Test
    void aServerError_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open();

        status = 503;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(source.status().version()).isEqualTo(1);
    }

    @Test
    void withNothingLoaded_everyReadIsRefused_untilTheServerRecovers() {
        status = 500;
        ReloadingMetadataSource source = open();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);

        status = 200;
        settle(source);
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aSlowServer_timesOut_andKeepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open(settings("/permissions.yaml", "", "", Duration.ofMillis(300)));

        delayMs = 1500;
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aBodyThatStalls_timesOutToo() {
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("format: 1\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(3000);
            exchange.close();
        });

        ReloadingMetadataSource source = open(settings("/stall", "", "", Duration.ofMillis(400)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anOversizedBody_isRefused_withAKnownLength() {
        ReloadingMetadataSource source = open();

        body = GOOD_YAML + "# " + "x".repeat(MetadataDocumentParser.MAX_BYTES) + "\n";
        settle(source);

        assertThat(source.status().version()).isEqualTo(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anOversizedBody_isCutOffWhileStreaming_withNoLength() {
        server.createContext("/endless", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = "# xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n"
                    .repeat(1024).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int sent = 0; sent < 64; sent++) {
                    out.write(chunk);
                }
            } catch (IOException _) {
                // the client hung up once it had read more than the limit, as it should
            }
        });

        ReloadingMetadataSource source = open(settings("/endless", "", "", Duration.ofSeconds(5)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aBadDocument_keepsTheLastGoodVersion() {
        ReloadingMetadataSource source = open();

        body = BAD_RULE;
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }

    @Test
    void theBearerToken_isSent_whenConfigured() {
        open(settings("/permissions.yaml", TOKEN, "", Duration.ofSeconds(5)));

        assertThat(authorization).contains("Bearer " + TOKEN);
    }

    @Test
    void noToken_meansNoAuthorizationHeader() {
        open();

        assertThat(authorization).containsOnly("null");
    }

    @Test
    void theTokenFile_isReadOnEveryPoll_soARotationIsPickedUp() throws IOException {
        Path tokenFile = Files.writeString(dir.resolve("token"), "first\n");
        AtomicLong now = new AtomicLong(1_000);
        ReloadingMetadataSource source = UrlMetadataSource.create(settings("/permissions.yaml", "", tokenFile.toString(),
                Duration.ofSeconds(5)), ReservedSchemas.fromConfig(""), PROJECT, new SimpleMeterRegistry(), now::get);
        opened.add(source);
        source.start();
        assertThat(authorization).contains("Bearer first");

        Files.writeString(tokenFile, "second-token\n");
        now.addAndGet(60_000);
        body = GOOD_YAML_TWO_RULES;
        settle(source);

        assertThat(authorization).contains("Bearer second-token");
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aTokenWithANewline_isRefusedWithoutEverBeingSent_orLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReloadingMetadataSource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReloadingMetadataSource source = open(settings("/permissions.yaml", "abc\ndef-secret", "",
                    Duration.ofSeconds(5)));
            source.refresh();

            assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
            assertThat(authorization).isEmpty();
            assertThat(appender.list).isNotEmpty().noneMatch(event -> event.getFormattedMessage().contains("def-secret")
                    || String.valueOf(event.getThrowableProxy()).contains("def-secret"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aTokenWithATrailingNewline_isTrimmedAndSent() {
        open(settings("/permissions.yaml", "  padded-token\n", "", Duration.ofSeconds(5)));

        assertThat(authorization).contains("Bearer padded-token");
    }

    @Test
    void aMissingTokenFile_isReportedAsTheUrlSourcesToken_notTheProvisioningOne() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReloadingMetadataSource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            open(settings("/permissions.yaml", "", dir.resolve("absent").toString(), Duration.ofSeconds(5)));

            assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("metadata.url.token")
                    && !event.getFormattedMessage().contains("provisioning"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aBadLocationHeader_isARefusal_notACrash() {
        server.createContext("/badlocation", exchange -> redirect(exchange, "http://[bad host/x"));

        ReloadingMetadataSource source = open(settings("/badlocation", "", "", Duration.ofSeconds(5)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void aNonNumericContentLength_isARefusal_notACrash() throws IOException {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> answerRaw(raw));
            UrlSettings settings = new UrlSettings("http://127.0.0.1:" + raw.getLocalPort() + "/p", "", "", false,
                    Duration.ofHours(1), Duration.ofSeconds(2), Duration.ofSeconds(2));

            ReloadingMetadataSource source = open(settings);

            assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        }
    }

    private static void answerRaw(ServerSocket raw) {
        try (Socket socket = raw.accept()) {
            socket.getInputStream().read(new byte[4096]);
            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\n" + GOOD_YAML)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException _) {
            // the client may hang up as soon as it reads the bad header
        }
    }

    @Test
    void theToken_isNeverLogged_evenWhenTheServerRefusesIt() {
        Logger logger = (Logger) LoggerFactory.getLogger(ReloadingMetadataSource.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            status = 401;
            open(settings("/permissions.yaml", TOKEN, "", Duration.ofSeconds(5)));

            assertThat(appender.list).isNotEmpty()
                    .noneMatch(event -> event.getFormattedMessage().contains(TOKEN)
                            || String.valueOf(event.getThrowableProxy()).contains(TOKEN));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aRedirectToAnotherHost_isRefused_andTheTargetIsNeverContacted() {
        ReloadingMetadataSource source = open(settings("/away", TOKEN, "", Duration.ofSeconds(5)));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(elsewhereHits).hasValue(0);
    }

    @Test
    void aRedirectWithinTheSameHost_isFollowed() {
        ReloadingMetadataSource source = open(settings("/moved", "", "", Duration.ofSeconds(5)));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void plainHttpToAnOutsideHost_isRefusedAtStart() {
        UrlSettings insecure = new UrlSettings("http://config.example.com/p.yaml", "", "", false,
                Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofSeconds(1));

        ReservedSchemas reserved = ReservedSchemas.fromConfig("");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> UrlMetadataSource.create(insecure, reserved, PROJECT, registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-insecure");
    }
}
