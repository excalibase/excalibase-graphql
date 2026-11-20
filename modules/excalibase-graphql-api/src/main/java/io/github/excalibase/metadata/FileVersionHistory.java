package io.github.excalibase.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The versions the {@code file} source has replaced, in one file next to the document ({@code permissions.history}).
 * The format is JSON Lines: one entry per line, {@code {"id","at","hash","text"}}, the text JSON-escaped, so a line
 * never holds a raw line break and a reader can tell where an entry ends. The whole file is written to a temporary
 * file, flushed and renamed over the old one, like the rules file, so a crash leaves the old history whole.
 * The newest entries are last in the file; at most {@code maxEntries} are kept and the file never exceeds
 * {@code maxBytes}. The file holds old rules, so it gets the permissions of the rules file.
 * An entry that does not parse, or whose hash is not that of its text, is dropped with a warning: damage to one line
 * costs that line only, and a file that is not text at all starts the history afresh.
 */
final class FileVersionHistory implements DocumentHistory {

    static final int MAX_ENTRIES = 20;
    /** One document at its size limit for every kept version. */
    static final long MAX_BYTES = (long) MAX_ENTRIES * MetadataDocumentParser.MAX_BYTES;

    private static final Logger log = LoggerFactory.getLogger(FileVersionHistory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final Path rules;
    private final int maxEntries;
    private final long maxBytes;
    private final FileDocumentWriter.BeforeRename beforeRename;

    FileVersionHistory(Path file, Path rules, int maxEntries, long maxBytes,
                       FileDocumentWriter.BeforeRename beforeRename) {
        this.file = file;
        this.rules = rules;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.beforeRename = beforeRename;
    }

    /** The history of a rules file: {@code permissions.yaml} is kept in {@code permissions.history}. */
    static FileVersionHistory beside(Path rules) {
        if (fileFor(rules).equals(rules)) {
            throw new IllegalArgumentException("the rules file " + rules.getFileName()
                    + " would share its name with its own history: rename it, the history is kept in a .history file");
        }
        return new FileVersionHistory(fileFor(rules), rules, MAX_ENTRIES, MAX_BYTES, _ -> { });
    }

    static Path fileFor(Path rules) {
        String name = rules.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return rules.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".history");
    }

    /**
     * Adds a text a save is about to replace and trims the oldest. A file that cannot be read is left exactly as it
     * is; a damaged one is kept beside the history as {@code .corrupt-<time>} before it is rewritten.
     *
     * @return the new entry's id
     * @throws IOException the text cannot be kept and the history is unchanged: the file cannot be read, the text is
     *                     larger than the cap, or the write failed
     */
    synchronized long append(String text, Instant at) throws IOException {
        Loaded loaded = load();
        if (loaded.unreadable() != null) {
            throw loaded.unreadable();
        }
        List<Entry> kept = new ArrayList<>(loaded.entries());
        long id = Math.max(at.toEpochMilli(), kept.stream().mapToLong(Entry::id).max().orElse(0) + 1);
        kept.add(new Entry(id, at, hash(text), text));
        List<byte[]> lines = trimmed(kept);
        if (loaded.damaged()) {
            keepAside(at, loaded.entries().isEmpty());
        }
        replaceWith(lines);
        return id;
    }

    /** Takes one entry out again, for a save that did not happen; an id that is not there changes nothing. */
    synchronized void discard(long id) throws IOException {
        Loaded loaded = load();
        if (loaded.unreadable() != null) {
            throw loaded.unreadable();
        }
        List<Entry> kept = new ArrayList<>(loaded.entries());
        if (kept.removeIf(entry -> entry.id() == id)) {
            replaceWith(lines(kept));
        }
    }

    @Override
    public synchronized List<Entry> list() {
        List<Entry> newestFirst = new ArrayList<>(load().entries());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Override
    public synchronized boolean readable() {
        return load().unreadable() == null;
    }

    @Override
    public synchronized Optional<Entry> get(long id) {
        return load().entries().stream().filter(entry -> entry.id() == id).findFirst();
    }

    /** The lines to write: the oldest dropped until the count and the total size fit, each line measured once. */
    private List<byte[]> trimmed(List<Entry> chronological) throws IOException {
        List<byte[]> lines = lines(chronological);
        long total = lines.stream().mapToLong(line -> line.length).sum();
        int first = 0;
        while (lines.size() - first > maxEntries || (lines.size() - first > 1 && total > maxBytes)) {
            total -= lines.get(first++).length;
        }
        if (total > maxBytes) {
            throw new IOException("the text is larger than the history may hold");
        }
        return lines.subList(first, lines.size());
    }

    /**
     * What is on disk, oldest first. A line that is not valid UTF-8, not JSON or whose hash is not its text's is
     * dropped on its own; a file too large to be ours counts as damaged whole; a file that cannot be read says so.
     */
    private Loaded load() {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE - 8L) + 1);
        } catch (NoSuchFileException _) {
            return new Loaded(List.of(), false, null);
        } catch (IOException e) {
            log.warn("metadata_history_unreadable file={} error={}", file.getFileName(), e.getClass().getSimpleName());
            return new Loaded(List.of(), false, e);
        }
        if (bytes.length > maxBytes) {
            log.warn("metadata_history_corrupt file={} reason=too_large", file.getFileName());
            return new Loaded(List.of(), true, null);
        }
        return parse(bytes);
    }

    private Loaded parse(byte[] bytes) {
        List<Entry> entries = new ArrayList<>();
        int dropped = 0;
        int start = 0;
        for (int end = 0; end <= bytes.length; end++) {
            if (end < bytes.length && bytes[end] != '\n') {
                continue;
            }
            if (end > start) {
                Optional<Entry> entry = entryOf(bytes, start, end);
                if (entry.isPresent()) {
                    entries.add(entry.get());
                } else {
                    dropped++;
                }
            }
            start = end + 1;
        }
        if (dropped > 0) {
            log.warn("metadata_history_entries_dropped file={} count={}", file.getFileName(), dropped);
        }
        return new Loaded(entries, dropped > 0, null);
    }

    private record Loaded(List<Entry> entries, boolean damaged, IOException unreadable) {
    }

    /** Keeps what was on disk before it is rewritten: moved when nothing in it could be used, copied otherwise. */
    private void keepAside(Instant at, boolean nothingUsable) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + at.toEpochMilli() + "-" + randomSuffix());
        if (nothingUsable) {
            Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.copy(file, aside, StandardCopyOption.REPLACE_EXISTING);
        }
        log.warn("metadata_history_kept_aside file={} aside={}", file.getFileName(), aside.getFileName());
    }

    private static Optional<Entry> entryOf(byte[] bytes, int start, int end) {
        try {
            String line = FileMetadataSource.decodeUtf8(Arrays.copyOfRange(bytes, start, end));
            return entryOf(line);
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    private static Optional<Entry> entryOf(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            if (node == null || !node.path("id").canConvertToLong() || !node.path("text").isTextual()
                    || !node.path("hash").isTextual() || !node.path("at").isTextual()) {
                return Optional.empty();
            }
            String text = node.get("text").asText();
            String hash = node.get("hash").asText();
            if (!hash.equals(hash(text))) {
                return Optional.empty();
            }
            return Optional.of(new Entry(node.get("id").asLong(), Instant.parse(node.get("at").asText()), hash, text));
        } catch (IOException | RuntimeException _) {
            return Optional.empty();
        }
    }

    private static String hash(String text) {
        return FileMetadataSource.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<byte[]> lines(List<Entry> chronological) throws IOException {
        List<byte[]> lines = new ArrayList<>();
        for (Entry entry : chronological) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", entry.id());
            node.put("at", entry.at().toString());
            node.put("hash", entry.hash());
            node.put("text", entry.text());
            lines.add((MAPPER.writeValueAsString(node) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return lines;
    }

    private void replaceWith(List<byte[]> lines) throws IOException {
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        lines.forEach(joined::writeBytes);
        byte[] content = joined.toByteArray();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + randomSuffix());
        try {
            flushed(temporary, content);
            beforeRename.run(temporary);
            move(temporary);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private void flushed(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel = FilePermissions.createOwnerOnly(temporary)) {
            FilePermissions.copyBestEffort(FilePermissions::copy, rules, temporary);
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void move(Path temporary) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("the folder does not support an atomic rename", e);
        }
        FilePermissions.syncFolder(file.toAbsolutePath().getParent());
    }

    private static String randomSuffix() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
