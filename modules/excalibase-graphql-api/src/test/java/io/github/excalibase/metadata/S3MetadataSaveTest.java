package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Saving to the {@code s3} source against a real MinIO: a SigV4-signed PUT that carries {@code If-Match}. */
class S3MetadataSaveTest {

    private static final String BUCKET = "meta";
    private static final String KEY = "permissions.yaml";
    private static MinioFixture minio;

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
    }

    @AfterEach
    void close() {
        if (source != null) {
            source.close();
        }
    }

    private ReloadingMetadataSource open(String secretKey) {
        S3Settings settings = S3MetadataSourceTest.settings(minio.endpoint(), BUCKET, KEY, MinioFixture.ACCESS_KEY,
                secretKey);
        source = S3MetadataSource.create(settings, ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false);
        source.start();
        return source;
    }

    private ReloadingMetadataSource openWithDocument() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        open(MinioFixture.SECRET_KEY);
        source.refresh();
        assertThat(source.current()).isPresent();
        return source;
    }

    @Test
    void minioHonoursConditionalWrites_soTheSourceIsWritable() {
        assertThat(openWithDocument().writable()).isTrue();
    }

    @Test
    void aSave_putsTheObject_andIsServedAfterTheReload() {
        ReloadingMetadataSource served = openWithDocument();

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(((SaveOutcome.Saved) outcome).sourceVersion()).isEqualTo(served.current().orElseThrow().sourceVersion());
    }

    @Test
    void aStaleVersion_isAConflict_andTheObjectIsUntouched() {
        ReloadingMetadataSource served = openWithDocument();

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, "sha256:stale");

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Conflict.class, conflict ->
                assertThat(conflict.currentVersion()).isEqualTo(served.current().orElseThrow().sourceVersion()));
        served.refresh();
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void anObjectReplacedBehindTheEngine_isAConflict() {
        ReloadingMetadataSource served = openWithDocument();
        String expected = served.current().orElseThrow().sourceVersion();
        minio.put(BUCKET, KEY, GOOD_YAML + "# someone else\n");

        assertThat(served.save(GOOD_YAML_TWO_RULES, expected)).isInstanceOf(SaveOutcome.Conflict.class);
    }

    @Test
    void twoWritersWithTheSameVersion_exactlyOneWins() throws Exception {
        ReloadingMetadataSource served = openWithDocument();
        String expected = served.current().orElseThrow().sourceVersion();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<SaveOutcome>> running = List.of(
                    pool.submit(contend(go, served, GOOD_YAML_TWO_RULES, expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# b\n", expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# c\n", expected)),
                    pool.submit(contend(go, served, GOOD_YAML + "# d\n", expected)));
            go.countDown();
            int saved = 0;
            for (Future<SaveOutcome> run : running) {
                SaveOutcome outcome = run.get();
                saved += outcome instanceof SaveOutcome.Saved ? 1 : 0;
                assertThat(outcome).isNotInstanceOf(SaveOutcome.Failed.class);
            }
            assertThat(saved).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<SaveOutcome> contend(CountDownLatch go, ReloadingMetadataSource served, String text,
                                                  String expected) {
        return () -> {
            go.await();
            return served.save(text, expected);
        };
    }

    @Test
    void credentialsTheStoreRefuses_leaveTheSourceReadOnly_andTheObjectIsUntouched() {
        minio.put(BUCKET, KEY, GOOD_YAML);
        ReloadingMetadataSource refused = open("a-wrong-secret-key-0123456789-abcdef");

        assertThat(refused.save(GOOD_YAML_TWO_RULES, MetadataWriter.NO_DOCUMENT))
                .isInstanceOf(SaveOutcome.ReadOnly.class);
        assertThat(refused.writable()).isFalse();
        ReloadingMetadataSource served = openWithDocument();
        served.refresh();
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void anInvalidDocument_isRefused_andNeverPut() {
        ReloadingMetadataSource served = openWithDocument();

        assertThat(served.save(BAD_UNKNOWN_KEY, served.current().orElseThrow().sourceVersion()))
                .isInstanceOf(SaveOutcome.Invalid.class);
        served.refresh();
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aFirstDocument_canBeSaved_whenTheObjectDoesNotExist() {
        ReloadingMetadataSource served = open(MinioFixture.SECRET_KEY);
        assertThat(served.current()).isEmpty();

        assertThat(served.save(GOOD_YAML, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(served.save(GOOD_YAML_TWO_RULES, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Conflict.class);
    }
}
