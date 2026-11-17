package io.github.excalibase.metadata;

import io.github.excalibase.permissions.PermissionsUnavailableException;
import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.github.excalibase.metadata.MetadataFixtures.*;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FileMetadataSourceTest {

    @TempDir
    Path dir;

    private final List<ReloadingMetadataSource> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(ReloadingMetadataSource::close);
    }

    private ReloadingMetadataSource open(Path path, Duration interval) {
        ReloadingMetadataSource source = FileMetadataSource.create(path, interval, ReservedSchemas.fromConfig(""),
                PROJECT, new SimpleMeterRegistry());
        opened.add(source);
        source.start();
        return source;
    }

    private static void settle(ReloadingMetadataSource source) {
        source.refresh();
        source.refresh();
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    @Test
    void theFile_isReadAtStartup() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);

        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.name()).isEqualTo("file");
        assertThat(source.status().sourceVersion()).hasSize(64);
    }

    @Test
    void aFolder_meansPermissionsYamlInsideIt() throws IOException {
        write(FileMetadataSource.FILE_NAME, GOOD_YAML);

        ReloadingMetadataSource source = open(dir, Duration.ofHours(1));

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aChangeOnDisk_isPickedUpByTheHashPollWithinABound() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);
        ReloadingMetadataSource source = open(file, Duration.ofMillis(30));

        Files.writeString(file, GOOD_YAML_TWO_RULES);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2));
    }

    @Test
    void aBadEdit_keepsTheLastGoodVersion() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);
        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        Files.writeString(file, BAD_RULE);
        settle(source);

        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isNotNull();
    }

    @Test
    void aDeletedFile_keepsTheLastGoodVersion_andAReturnedFileIsPickedUp() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);
        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        Files.delete(file);
        settle(source);
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
        assertThat(source.status().lastRefusal()).isNotNull();

        Files.writeString(file, GOOD_YAML_TWO_RULES);
        settle(source);
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }

    @Test
    void aBadFileAtStartup_refusesEverything() throws IOException {
        Path file = write("permissions.yaml", BAD_RULE);

        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);
    }

    @Test
    void aMissingFileAtStartup_refusesEverything_andIsServedOnceItAppears() throws IOException {
        Path file = dir.resolve("permissions.yaml");

        ReloadingMetadataSource source = open(file, Duration.ofHours(1));
        assertThatThrownBy(() -> source.permissionsFor(PROJECT)).isInstanceOf(PermissionsUnavailableException.class);

        Files.writeString(file, GOOD_YAML);
        settle(source);
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);
    }

    @Test
    void aFileOverOneMiB_isRefused() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML + "# " + "x".repeat(MetadataDocumentParser.MAX_BYTES) + "\n");

        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        assertThat(source.status().lastRefusal()).isEqualTo("invalid_document");
    }

    /** A Kubernetes ConfigMap volume: the file is a link to ..data/file, and ..data is swapped by a rename. */
    @Test
    void aConfigMapSymlinkSwap_isPickedUp() throws IOException {
        Path first = Files.createDirectory(dir.resolve("..2026_11_17_a"));
        Files.writeString(first.resolve("permissions.yaml"), GOOD_YAML);
        Files.createSymbolicLink(dir.resolve("..data"), first.getFileName());
        Files.createSymbolicLink(dir.resolve("permissions.yaml"), Path.of("..data/permissions.yaml"));
        ReloadingMetadataSource source = open(dir, Duration.ofMillis(30));
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(1);

        Path second = Files.createDirectory(dir.resolve("..2026_11_17_b"));
        Files.writeString(second.resolve("permissions.yaml"), GOOD_YAML_TWO_RULES);
        Path swap = Files.createSymbolicLink(dir.resolve("..data_tmp"), second.getFileName());
        Files.move(swap, dir.resolve("..data"), ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(first.resolve("permissions.yaml"));
        Files.delete(first);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2));
        assertThat(source.status().lastRefusal()).isNull();
    }

    @Test
    void aWritableFolder_isWritable_andAReadOnlyOneIsNot() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);
        ReloadingMetadataSource source = open(file, Duration.ofHours(1));
        assertThat(source.writable()).isTrue();

        assertThat(dir.toFile().setWritable(false)).isTrue();
        try {
            assertThat(source.writable()).isFalse();
        } finally {
            dir.toFile().setWritable(true);
        }
    }

    @Test
    void aFileCaughtHalfWritten_isNotSwappedIn_untilItHasStoppedChanging() throws IOException {
        Path file = write("permissions.yaml", GOOD_YAML);
        ReloadingMetadataSource source = open(file, Duration.ofHours(1));

        Files.writeString(file, GOOD_YAML_TWO_RULES.substring(0, GOOD_YAML_TWO_RULES.indexOf("role: admin") + 4));
        source.refresh();
        Files.writeString(file, GOOD_YAML_TWO_RULES);
        source.refresh();
        assertThat(source.status().version()).isEqualTo(1);

        source.refresh();
        assertThat(source.permissionsFor(PROJECT).tables()).hasSize(2);
    }
}
