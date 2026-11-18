package io.github.excalibase.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tokens and malformed or hostile responses: each is a refusal that keeps the engine up, never a crash or a leak. */
class UrlMetadataSourceHardeningTest extends UrlSourceFixture {

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
                Duration.ofSeconds(5)), ReservedSchemas.fromConfig(""), PROJECT, new SimpleMeterRegistry(), now::get, false);
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
    void aDeclaredLengthOverTheLimit_isRefusedBeforeAnyBodyIsRead() throws IOException {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> declareHugeBodyThenStall(raw));
            UrlSettings settings = new UrlSettings("http://127.0.0.1:" + raw.getLocalPort() + "/p", "", "", false,
                    Duration.ofHours(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
            long started = System.nanoTime();

            ReloadingMetadataSource source = open(settings);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        }
    }

    private void declareHugeBodyThenStall(ServerSocket raw) {
        try (Socket socket = raw.accept()) {
            socket.getInputStream().read(new byte[4096]);
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5242880\r\n\r\nformat: 1\n"
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            release.await(6, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException _) {
            // the client hung up, as it should
        }
    }
}
