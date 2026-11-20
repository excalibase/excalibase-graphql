package io.github.excalibase.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The history file: one JSON Lines file holding the last versions of the document, replaced whole and atomically. */
class FileVersionHistoryTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    @TempDir
    Path dir;

    private Path history() {
        return dir.resolve("permissions.history");
    }

    private Path rules() throws IOException {
        Path rules = dir.resolve("permissions.yaml");
        return Files.exists(rules) ? rules : Files.writeString(rules, "format: 1\n");
    }

    private FileVersionHistory open(int entries, long bytes) throws IOException {
        return new FileVersionHistory(history(), rules(), entries, bytes, _ -> { });
    }

    private FileVersionHistory open() throws IOException {
        return open(20, 1 << 20);
    }

    @AfterEach
    void restore() {
        dir.toFile().setWritable(true);
    }

    private List<String> strayFiles() throws IOException {
        try (Stream<Path> all = Files.list(dir)) {
            return all.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("permissions.yaml") && !name.equals("permissions.history")).toList();
        }
    }

    @Test
    void aRecordedText_comesBackWhole_withItsTimeAndHash_comments_andAllLineEndings() throws IOException {
        FileVersionHistory history = open();
        String text = "# keep me\r\nformat: 1\n\ttables: []   \n\"quoted\" \\ unicode: é中\n";

        long id = history.append(text, T0);

        assertThat(history.list()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(id);
            assertThat(entry.at()).isEqualTo(T0);
            assertThat(entry.text()).isEqualTo(text);
            assertThat(entry.hash()).isEqualTo(FileMetadataSource.sha256(text.getBytes(StandardCharsets.UTF_8)));
        });
        assertThat(history.get(id)).isPresent();
        assertThat(history.get(id + 1)).isEmpty();
    }

    @Test
    void oneFileHoldsManyVersions_oneLineEach_newestFirst() throws IOException {
        FileVersionHistory history = open();
        history.append("one\n", T0);
        history.append("two\nlines\n", T0.plusSeconds(1));
        history.append("three\n", T0.plusSeconds(2));

        assertThat(history.list()).extracting(DocumentHistory.Entry::text)
                .containsExactly("three\n", "two\nlines\n", "one\n");
        assertThat(Files.readAllLines(history())).hasSize(3);
        assertThat(strayFiles()).isEmpty();
    }

    @Test
    void idsNeverRepeat_evenAfterTheOldestAreTrimmed() throws IOException {
        FileVersionHistory history = open(2, 1 << 20);
        long first = history.append("a", T0);
        long second = history.append("b", T0);
        long third = history.append("c", T0);

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void onlyTheLastTwentyVersionsAreKept_theOldestGoFirst() throws IOException {
        FileVersionHistory history = open();
        for (int i = 1; i <= 25; i++) {
            history.append("version " + i + "\n", T0.plusSeconds(i));
        }

        List<DocumentHistory.Entry> kept = history.list();
        assertThat(kept).hasSize(20);
        assertThat(kept.getFirst().text()).isEqualTo("version 25\n");
        assertThat(kept.getLast().text()).isEqualTo("version 6\n");
        assertThat(Files.readAllLines(history())).hasSize(20);
    }

    @Test
    void theTotalSizeIsBounded_theOldestGoUntilTheFileFits() throws IOException {
        long cap = 1_000;
        FileVersionHistory history = open(20, cap);
        for (int i = 0; i < 10; i++) {
            history.append(String.valueOf(i).repeat(300), T0.plusSeconds(i));
        }

        assertThat(Files.size(history())).isLessThanOrEqualTo(cap);
        assertThat(history.list()).isNotEmpty().hasSizeLessThan(10);
        assertThat(history.list().getFirst().text()).startsWith("9");
    }

    @Test
    void aSingleTextLargerThanTheCap_isNotRecorded_andNothingGrows() throws IOException {
        FileVersionHistory history = open(20, 200);
        history.append("small\n", T0);

        assertThatThrownBy(() -> history.append("x".repeat(500), T0)).isInstanceOf(IOException.class);

        assertThat(history.list()).extracting(DocumentHistory.Entry::text).containsExactly("small\n");
        assertThat(Files.size(history())).isLessThanOrEqualTo(200);
    }

    @Test
    void aCrashBetweenTheTemporaryFileAndTheRename_leavesTheHistoryAsItWas() throws IOException {
        open().append("before\n", T0);
        byte[] before = Files.readAllBytes(history());
        FileVersionHistory crashing = new FileVersionHistory(history(), rules(), 20, 1 << 20, temporary -> {
            assertThat(Files.readString(temporary)).contains("after");
            throw new IOException("simulated crash");
        });

        assertThatThrownBy(() -> crashing.append("after\n", T0.plusSeconds(1))).isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(history())).isEqualTo(before);
        assertThat(open().list()).extracting(DocumentHistory.Entry::text).containsExactly("before\n");
        assertThat(strayFiles()).isEmpty();
    }

    @Test
    void aCorruptHistoryFile_doesNotBlockARecord_itStartsFresh() throws IOException {
        Files.writeString(history(), "this is not json\n{\"id\": broken\n\u0000\u0001garbage");
        FileVersionHistory history = open();

        assertThat(history.list()).isEmpty();
        history.append("fresh\n", T0);

        assertThat(history.list()).extracting(DocumentHistory.Entry::text).containsExactly("fresh\n");
        assertThat(Files.readAllLines(history())).hasSize(1);
    }

    @Test
    void aBinaryOrOversizedHistoryFile_isCorrupt_notLoaded() throws IOException {
        Files.write(history(), new byte[]{(byte) 0xff, (byte) 0xfe, 0x00, (byte) 0xc3, 0x28});
        assertThat(open().list()).isEmpty();
        Files.write(history(), new byte[5_000]);
        assertThat(open(20, 1_000).list()).isEmpty();
        assertThat(open(20, 1_000).append("ok\n", T0)).isPositive();
    }

    @Test
    void anEntryWhoseHashDoesNotMatchItsText_isDropped_theGoodOnesStay() throws IOException {
        FileVersionHistory history = open();
        history.append("good\n", T0);
        String tampered = "{\"id\":9999999999999,\"at\":\"2026-11-20T10:00:00Z\",\"hash\":\""
                + "0".repeat(64) + "\",\"text\":\"edited by hand\"}\n";
        Files.writeString(history(), tampered + Files.readString(history()));

        assertThat(history.list()).extracting(DocumentHistory.Entry::text).containsExactly("good\n");
    }

    @Test
    void aReadOnlyFolder_cannotRecord_butStillListsWhatIsThere() throws IOException {
        FileVersionHistory history = open();
        history.append("kept\n", T0);
        assertThat(dir.toFile().setWritable(false)).isTrue();

        assertThatThrownBy(() -> history.append("new\n", T0)).isInstanceOf(IOException.class);

        assertThat(history.list()).extracting(DocumentHistory.Entry::text).containsExactly("kept\n");
    }

    @Test
    void aMissingFolder_hasNoHistory_andNoError() throws IOException {
        FileVersionHistory history = new FileVersionHistory(dir.resolve("nope").resolve("permissions.history"),
                rules(), 20, 1 << 20, _ -> { });

        assertThat(history.list()).isEmpty();
        assertThat(history.get(1)).isEmpty();
    }

    @Test
    void discard_removesOneEntryByItsId_andNoOtherId() throws IOException {
        FileVersionHistory history = open();
        long first = history.append("a\n", T0);
        long second = history.append("b\n", T0);

        history.discard(second);

        assertThat(history.list()).extracting(DocumentHistory.Entry::id).containsExactly(first);
        history.discard(second);
        assertThat(history.list()).hasSize(1);
    }

    @Test
    void theHistoryFileHasTheRulesFilesPermissions() throws IOException {
        Path rules = rules();
        Files.setPosixFilePermissions(rules, PosixFilePermissions.fromString("rw-------"));
        open().append("a\n", T0);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(history()))).isEqualTo("rw-------");

        Files.setPosixFilePermissions(rules, PosixFilePermissions.fromString("rw-r-----"));
        open().append("b\n", T0);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(history()))).isEqualTo("rw-r-----");
    }

    @Test
    void theHistoryFileIsNamedAfterTheRulesFile() {
        assertThat(FileVersionHistory.fileFor(Path.of("/x/permissions.yaml"))).isEqualTo(Path.of("/x/permissions.history"));
        assertThat(FileVersionHistory.fileFor(Path.of("/x/rules.yml"))).isEqualTo(Path.of("/x/rules.history"));
        assertThat(FileVersionHistory.fileFor(Path.of("/x/noextension"))).isEqualTo(Path.of("/x/noextension.history"));
    }
}
