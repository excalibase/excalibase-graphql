package io.github.excalibase.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileWriterTest {

    @TempDir
    Path dir;

    @Test
    void writesTheContent_andReturnsItsHash_andLeavesNoTempFile() throws IOException {
        Path target = dir.resolve("permissions.yaml");

        String version = AtomicFileWriter.write(target, "hello", null);

        assertThat(Files.readString(target)).isEqualTo("hello");
        assertThat(version).isEqualTo(ContentHash.of("hello".getBytes(StandardCharsets.UTF_8)));
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("permissions.yaml");
        }
    }

    @Test
    void aReaderNeverSeesAPartialFile() throws Exception {
        Path target = dir.resolve("permissions.yaml");
        String first = "a".repeat(200_000);
        String second = "b".repeat(200_000);
        AtomicFileWriter.write(target, first, null);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try {
                    String seen = Files.readString(target);
                    if (!seen.equals(first) && !seen.equals(second)) {
                        torn.set("saw " + seen.length() + " chars");
                    }
                } catch (IOException e) {
                    torn.set(e.toString());
                }
            }
        });
        reader.start();
        for (int round = 0; round < 200; round++) {
            AtomicFileWriter.write(target, round % 2 == 0 ? second : first, null);
        }
        done.set(true);
        reader.join();

        assertThat(torn.get()).isNull();
    }

    @Test
    void aSaveStartedFromTheCurrentVersion_succeeds() throws IOException {
        Path target = dir.resolve("permissions.yaml");
        String version = AtomicFileWriter.write(target, "one", null);

        AtomicFileWriter.write(target, "two", version);

        assertThat(Files.readString(target)).isEqualTo("two");
    }

    @Test
    void aSaveStartedFromAnOldVersion_isAConflict_andChangesNothing() throws IOException {
        Path target = dir.resolve("permissions.yaml");
        String stale = AtomicFileWriter.write(target, "one", null);
        AtomicFileWriter.write(target, "two", stale);

        assertThatThrownBy(() -> AtomicFileWriter.write(target, "three", stale))
                .isInstanceOf(MetadataConflictException.class);
        assertThat(Files.readString(target)).isEqualTo("two");
    }

    @Test
    void aSaveThatExpectsAFile_whenThereIsNone_isAConflict() {
        assertThatThrownBy(() -> AtomicFileWriter.write(dir.resolve("permissions.yaml"), "x", "abc"))
                .isInstanceOf(MetadataConflictException.class);
    }

    @Test
    void aFailedWrite_leavesNoTempFileBehind() throws IOException {
        Path target = dir.resolve("missing-folder").resolve("permissions.yaml");

        assertThatThrownBy(() -> AtomicFileWriter.write(target, "x", null)).isInstanceOf(IOException.class);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(Set.copyOf(files.toList())).isEmpty();
        }
    }
}
