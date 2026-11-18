package io.github.excalibase.metadata;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Collectors;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code s3} source against a real MinIO: boot, live edits, refusals, missing objects and what never reaches a log. */
class S3MetadataSourceTest {

    private static final String BUCKET = "meta";
    private static final String KEY = "permissions.yaml";
    private static MinioFixture minio;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ReloadingMetadataSource source;

    @BeforeAll
    static void startMinio() {
        minio = new MinioFixture().start();
        minio.createBucket(BUCKET);
    }

    @AfterAll
    static void stopMinio() {
        minio.close();
    }

    @BeforeEach
    void cleanSlate() {
        minio.delete(BUCKET, KEY);
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void close() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        if (source != null) {
            source.close();
        }
    }

    static S3Settings settings(String endpoint, String bucket, String key, String access, String secret) {
        return new S3Settings(endpoint, bucket, key, MinioFixture.REGION, null, true,
                S3Settings.Credentials.literal(access, secret), Duration.ofHours(1), Duration.ofSeconds(2),
                Duration.ofSeconds(3));
    }

    private S3Settings settings() {
        return settings(minio.endpoint(), BUCKET, KEY, MinioFixture.ACCESS_KEY, MinioFixture.SECRET_KEY);
    }

    private ReloadingMetadataSource create(S3Settings settings, boolean envJwt) {
        source = S3MetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT, meters, envJwt);
        return source;
    }

    /** A changed document is believed only on the second identical read. */
    private static void poll(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void bootsFromAnObject_andReportsTheBodyHashAsTheVersion() {
        minio.put(BUCKET, KEY, GOOD_YAML);

        create(settings(), false).start();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.name()).isEqualTo("s3");
        assertThat(source.status().sourceVersion()).isEqualTo("sha256:" + MinioFixture.hex(GOOD_YAML.getBytes()));
        assertThat(source.writable()).isTrue();
    }

    @Test
    void anEditedObject_isPickedUp_onlyOnceTheFollowingPollSeesTheSameBody() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        create(settings(), false).start();

        minio.put(BUCKET, KEY, GOOD_YAML_TWO_RULES);
        source.refresh();
        assertThat(source.permissionsFor(PROJECT).tables()).as("not believed on first sight").hasSize(1);
        source.refresh();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void anInvalidEdit_isRefused_andTheLastGoodVersionKeepsServing() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        create(settings(), false).start();

        minio.put(BUCKET, KEY, BAD_RULE);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
        assertThat(logged()).contains("metadata_refused source=s3");
    }

    @Test
    void aMissingObject_atStart_refusesEverything_untilItAppears() {
        create(settings(), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(new MetadataHealth(source).ready()).isFalse();
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");

        minio.put(BUCKET, KEY, GOOD_YAML);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(new MetadataHealth(source).ready()).isTrue();
    }

    @Test
    void aMissingBucket_atStart_refusesEverything_andStaysNotReady() {
        create(settings(minio.endpoint(), "no-such-bucket", KEY, MinioFixture.ACCESS_KEY, MinioFixture.SECRET_KEY),
                false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(new MetadataHealth(source).ready()).isFalse();
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anObjectThatDisappears_midRun_keepsTheLastGoodVersion() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        create(settings(), false).start();

        minio.delete(BUCKET, KEY);
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
    }

    @Test
    void anObjectOverTheCap_isRefused_andNeverServed() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        create(settings(), false).start();

        minio.put(BUCKET, KEY, GOOD_YAML + "#" + "x".repeat(MetadataDocumentParser.MAX_BYTES));
        poll(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(logged()).contains("larger than");
    }

    @Test
    void anObjectOverTheCap_atStart_refusesEverything() {
        minio.put(BUCKET, KEY, GOOD_YAML + "#" + "x".repeat(MetadataDocumentParser.MAX_BYTES));

        create(settings(), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void wrongCredentials_areRefused_andNeitherTheSecretNorAnythingTheServerSaidIsLogged() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        String wrongSecret = "wrong-secret-VALUE-9876";

        create(settings(minio.endpoint(), BUCKET, KEY, MinioFixture.ACCESS_KEY, wrongSecret), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(source.status().lastRefusal()).isEqualTo("unreadable");
        assertThat(logged()).contains("metadata_refused source=s3").contains("403")
                .doesNotContain(wrongSecret).doesNotContain(MinioFixture.SECRET_KEY)
                .doesNotContain(MinioFixture.ACCESS_KEY).doesNotContain("SignatureDoesNotMatch");
    }

    @Test
    void anUnknownAccessKey_isRefusedToo() {
        minio.put(BUCKET, KEY, GOOD_YAML);

        create(settings(minio.endpoint(), BUCKET, KEY, "nobody-here", "whatever"), false).start();

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
        assertThat(logged()).doesNotContain("nobody-here");
    }

    @Test
    void keysWithSpacesPlusSignsAndUnicode_areSignedAndFetched() {
        for (String key : new String[] {"my docs/a+b.yaml", "unicod\u00e9/\u65e5\u672c/\u00fc.yaml", "a~b/c*d(e).yaml"}) {
            minio.put(BUCKET, key, GOOD_YAML);

            try (ReloadingMetadataSource served = S3MetadataSource.create(
                    settings(minio.endpoint(), BUCKET, key, MinioFixture.ACCESS_KEY, MinioFixture.SECRET_KEY),
                    ReservedSchemas.fromConfig(""), PROJECT, meters, false)) {
                served.start();

                assertThat(served.permissionsFor(PROJECT).tables()).as(key).hasSize(1);
            }
        }
    }

    @Test
    void anUpperCaseEndpointHost_isSignedAsTheLowerCaseOneTheClientSends() {
        minio.put(BUCKET, KEY, GOOD_YAML);

        create(settings("http://LOCALHOST:" + minio.port(), BUCKET, KEY, MinioFixture.ACCESS_KEY,
                MinioFixture.SECRET_KEY), false).start();

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }
}
