package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Saving to the {@code file} source: a conditional, atomic replace, then a reload through the ordinary path. */
class FileMetadataSaveTest {

    @TempDir
    Path dir;

    private ReloadingMetadataSource source;

    @AfterEach
    void close() {
        dir.toFile().setWritable(true);
        if (source != null) {
            source.close();
        }
    }

    private Path file() throws IOException {
        return Files.writeString(dir.resolve("permissions.yaml"), GOOD_YAML);
    }

    private ReloadingMetadataSource open(Path path, FileDocumentWriter writer) {
        source = FileMetadataSource.create(path, Duration.ofHours(1), ReservedSchemas.fromConfig(""), PROJECT,
                new SimpleMeterRegistry(), false, writer);
        source.start();
        return source;
    }

    private ReloadingMetadataSource open(Path path) {
        return open(path, new FileDocumentWriter(path));
    }

    private List<String> strayFiles() throws IOException {
        try (Stream<Path> all = Files.list(dir)) {
            return all.map(path -> path.getFileName().toString()).filter(name -> !name.equals("permissions.yaml"))
                    .toList();
        }
    }

    @Test
    void aSave_replacesTheFile_andIsServedAfterTheReload() throws IOException {
        ReloadingMetadataSource served = open(file());
        String expected = served.current().orElseThrow().sourceVersion();

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, expected);

        assertThat(outcome).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(Files.readString(dir.resolve("permissions.yaml"))).isEqualTo(GOOD_YAML_TWO_RULES);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(2);
        assertThat(((SaveOutcome.Saved) outcome).version()).isEqualTo(2);
        assertThat(((SaveOutcome.Saved) outcome).sourceVersion()).isEqualTo(served.current().orElseThrow().sourceVersion());
        assertThat(strayFiles()).isEmpty();
    }

    @Test
    void aStaleVersion_isAConflict_andNothingIsWritten() throws IOException {
        ReloadingMetadataSource served = open(file());

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, "not-the-version");

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Conflict.class, conflict ->
                assertThat(conflict.currentVersion()).isEqualTo(served.current().orElseThrow().sourceVersion()));
        assertThat(Files.readString(dir.resolve("permissions.yaml"))).isEqualTo(GOOD_YAML);
        assertThat(served.status().version()).isEqualTo(1);
    }

    @Test
    void aFileThatChangedBehindTheEngine_isAConflict_evenBeforeTheEngineHasPolledIt() throws IOException {
        Path path = file();
        ReloadingMetadataSource served = open(path);
        String expected = served.current().orElseThrow().sourceVersion();
        Files.writeString(path, GOOD_YAML + "\n# edited elsewhere\n");

        assertThat(served.save(GOOD_YAML_TWO_RULES, expected)).isInstanceOf(SaveOutcome.Conflict.class);
        assertThat(Files.readString(path)).contains("edited elsewhere");
    }

    @Test
    void twoWritersWithTheSameVersion_exactlyOneWins() throws Exception {
        ReloadingMetadataSource served = open(file());
        String expected = served.current().orElseThrow().sourceVersion();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<SaveOutcome>> writers = List.of(
                    () -> awaited(go, () -> served.save(GOOD_YAML_TWO_RULES, expected)),
                    () -> awaited(go, () -> served.save(GOOD_YAML + "# second\n", expected)));
            List<Future<SaveOutcome>> running = writers.stream().map(pool::submit).toList();
            go.countDown();
            long saved = 0;
            long conflicts = 0;
            for (Future<SaveOutcome> run : running) {
                SaveOutcome outcome = run.get();
                saved += outcome instanceof SaveOutcome.Saved ? 1 : 0;
                conflicts += outcome instanceof SaveOutcome.Conflict ? 1 : 0;
            }
            assertThat(saved).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static SaveOutcome awaited(CountDownLatch go, Callable<SaveOutcome> save) throws Exception {
        go.await();
        return save.call();
    }

    @Test
    void aCrashBeforeTheRename_leavesTheOldFileWhole_andTheLastGoodDocumentServed() throws IOException {
        Path path = file();
        ReloadingMetadataSource served = open(path, new FileDocumentWriter(path, temporary -> {
            assertThat(Files.readString(temporary)).isEqualTo(GOOD_YAML_TWO_RULES);
            throw new IOException("simulated crash");
        }));

        SaveOutcome outcome = served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOf(SaveOutcome.Failed.class);
        assertThat(Files.readString(path)).isEqualTo(GOOD_YAML);
        assertThat(strayFiles()).isEmpty();
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aReadOnlyFolder_isNotWritable_andASaveIsRefused() throws IOException {
        Path path = file();
        ReloadingMetadataSource served = open(path);
        assertThat(served.writable()).isTrue();
        assertThat(dir.toFile().setWritable(false)).isTrue();

        assertThat(served.writable()).isFalse();
        assertThat(served.save(GOOD_YAML_TWO_RULES, served.current().orElseThrow().sourceVersion()))
                .isInstanceOf(SaveOutcome.ReadOnly.class);
        assertThat(Files.readString(path)).isEqualTo(GOOD_YAML);
    }

    @Test
    void aFirstDocument_canBeSaved_whenNoneExistsYet() {
        Path path = dir.resolve("permissions.yaml");
        ReloadingMetadataSource served = open(path);
        assertThat(served.current()).isEmpty();

        assertThat(served.save(GOOD_YAML, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Saved.class);
        assertThat(served.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(served.save(GOOD_YAML, MetadataWriter.NO_DOCUMENT)).isInstanceOf(SaveOutcome.Conflict.class);
    }

    @Test
    void aSourceWithNoWriter_refusesEverySave() throws IOException {
        Path path = file();
        source = new ReloadingMetadataSource("url", () -> FileMetadataSource.read(path),
                new MetadataDocumentParser(ReservedSchemas.fromConfig(""), PROJECT, false)::load, () -> false,
                Duration.ofHours(1), new SimpleMeterRegistry());
        source.start();

        assertThat(source.save(GOOD_YAML_TWO_RULES, "anything")).isInstanceOf(SaveOutcome.ReadOnly.class);
        assertThat(source.writable()).isFalse();
    }

    @Test
    void anInvalidDocument_isRefusedByTheSource_andNeverWritten() throws IOException {
        Path path = file();
        ReloadingMetadataSource served = open(path);

        SaveOutcome outcome = served.save(BAD_UNKNOWN_KEY, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Invalid.class, invalid ->
                assertThat(invalid.errors()).isNotEmpty());
        assertThat(Files.readString(path)).isEqualTo(GOOD_YAML);
        assertThat(strayFiles()).isEmpty();
    }

    @Test
    void aSecretReferenceThatDoesNotResolve_isADocumentError_andNeverWritten() throws IOException {
        Path path = file();
        ReloadingMetadataSource served = open(path);
        String unresolved = GOOD_YAML.replace("tables:", """
                jwt:
                  secret: env:EXCALIBASE_SURELY_NOT_SET
                  audience: a
                tables:""");

        SaveOutcome outcome = served.save(unresolved, served.current().orElseThrow().sourceVersion());

        assertThat(outcome).isInstanceOfSatisfying(SaveOutcome.Invalid.class, invalid ->
                assertThat(invalid.errors().getFirst()).startsWith("jwt:"));
        assertThat(Files.readString(path)).isEqualTo(GOOD_YAML);
    }
}
