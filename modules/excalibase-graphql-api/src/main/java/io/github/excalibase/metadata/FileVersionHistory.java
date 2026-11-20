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
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
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
        return new FileVersionHistory(fileFor(rules), rules, MAX_ENTRIES, MAX_BYTES, _ -> { });
    }

    static Path fileFor(Path rules) {
        String name = rules.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return rules.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".history");
    }

    /**
     * Adds a text a save is about to replace and trims the oldest.
     *
     * @return the new entry's id
     * @throws IOException the text cannot be kept (the history is unchanged), including a text larger than the cap
     */
    synchronized long record(String text, Instant at) throws IOException {
        List<Entry> kept = new ArrayList<>(chronological());
        long id = Math.max(at.toEpochMilli(), kept.stream().mapToLong(Entry::id).max().orElse(0) + 1);
        kept.add(new Entry(id, at, hash(text), text));
        replaceWith(trimmed(kept));
        return id;
    }

    /** Takes one entry out again, for a save that did not happen; an id that is not there changes nothing. */
    synchronized void discard(long id) throws IOException {
        List<Entry> kept = chronological();
        if (kept.removeIf(entry -> entry.id() == id)) {
            replaceWith(kept);
        }
    }

    @Override
    public synchronized List<Entry> list() {
        List<Entry> newestFirst = new ArrayList<>(chronological());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Override
    public synchronized Optional<Entry> get(long id) {
        return chronological().stream().filter(entry -> entry.id() == id).findFirst();
    }

    private List<Entry> trimmed(List<Entry> chronological) throws IOException {
        List<Entry> kept = new ArrayList<>(chronological);
        while (kept.size() > maxEntries) {
            kept.removeFirst();
        }
        while (kept.size() > 1 && serialized(kept).length > maxBytes) {
            kept.removeFirst();
        }
        if (serialized(kept).length > maxBytes) {
            throw new IOException("the text is larger than the history may hold");
        }
        return kept;
    }

    /** What is on disk, oldest first. Anything that cannot be trusted is left out, never thrown. */
    private List<Entry> chronological() {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) Math.min(maxBytes, Integer.MAX_VALUE - 8) + 1);
        } catch (NoSuchFileException _) {
            return new ArrayList<>();
        } catch (IOException e) {
            log.warn("metadata_history_unreadable file={} error={}", file.getFileName(), e.getClass().getSimpleName());
            return new ArrayList<>();
        }
        if (bytes.length > maxBytes) {
            log.warn("metadata_history_corrupt file={} reason=too_large", file.getFileName());
            return new ArrayList<>();
        }
        try {
            return parse(FileMetadataSource.decodeUtf8(bytes));
        } catch (IOException _) {
            log.warn("metadata_history_corrupt file={} reason=not_text", file.getFileName());
            return new ArrayList<>();
        }
    }

    private List<Entry> parse(String content) {
        List<Entry> entries = new ArrayList<>();
        int dropped = 0;
        for (String line : content.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Optional<Entry> entry = entryOf(line);
            if (entry.isPresent()) {
                entries.add(entry.get());
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.warn("metadata_history_entries_dropped file={} count={}", file.getFileName(), dropped);
        }
        return entries;
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

    private static byte[] serialized(List<Entry> chronological) throws IOException {
        StringBuilder lines = new StringBuilder();
        for (Entry entry : chronological) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", entry.id());
            node.put("at", entry.at().toString());
            node.put("hash", entry.hash());
            node.put("text", entry.text());
            lines.append(MAPPER.writeValueAsString(node)).append('\n');
        }
        return lines.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void replaceWith(List<Entry> chronological) throws IOException {
        byte[] content = serialized(chronological);
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
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            FilePermissions.copy(rules, temporary);
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
