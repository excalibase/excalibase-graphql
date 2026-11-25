package io.github.excalibase.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Saves the {@code file} source: the new text goes to {@code permissions.yaml.tmp-<random>} in the same folder, is
 * flushed to disk, and is renamed over the document, so a reader (or a crash) sees the old file or the new one, never
 * a part of it. The version check and the rename happen under one lock, which makes saves through this process
 * conditional; two replicas sharing one writable file are not guarded against each other, so use {@code db} or
 * {@code s3} for that.
 * <p>
 * Before the rename, the text being replaced is added to the history (see {@link FileVersionHistory}). The history
 * is a safety net, not a gate: if it cannot be written the save goes ahead with a warning, because refusing to save
 * rules over an unwritable history file would take the user's ability to change them away; and if the rename then
 * fails, the entry is taken out again, so the history never lists a version the file did not leave.
 */
final class FileDocumentWriter implements MetadataWriter {

    /** Runs after the temporary file is complete and before the rename; a test uses it to simulate a crash. */
    @FunctionalInterface
    interface BeforeRename {
        void run(Path temporary) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(FileDocumentWriter.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path path;
    private final BeforeRename beforeRename;
    private final FileVersionHistory history;
    private final FilePermissions.Copier permissions;

    FileDocumentWriter(Path path) {
        this(path, _ -> { });
    }

    FileDocumentWriter(Path path, BeforeRename beforeRename) {
        this(path, beforeRename, FileVersionHistory.beside(FileMetadataSource.resolve(path).toAbsolutePath()));
    }

    FileDocumentWriter(Path path, BeforeRename beforeRename, FileVersionHistory history) {
        this(path, beforeRename, history, FilePermissions::copy);
    }

    FileDocumentWriter(Path path, BeforeRename beforeRename, FileVersionHistory history,
                       FilePermissions.Copier permissions) {
        this.path = path;
        this.beforeRename = beforeRename;
        this.history = history;
        this.permissions = permissions;
    }

    @Override
    public Optional<DocumentHistory> history() {
        return Optional.of(history);
    }

    @Override
    public boolean writable() {
        Path folder = FileMetadataSource.resolve(path).toAbsolutePath().getParent();
        return folder != null && Files.isDirectory(folder) && Files.isWritable(folder);
    }

    @Override
    public String write(String text, String expectedVersion) throws IOException, WriteConflictException {
        return store(text, expectedVersion).version();
    }

    @Override
    public synchronized Written store(String text, String expectedVersion) throws IOException, WriteConflictException {
        ReloadingMetadataSource.Raw current = readCurrent();
        String currentVersion = current == null ? NO_DOCUMENT : current.version();
        if (!currentVersion.equals(expectedVersion)) {
            throw new WriteConflictException(currentVersion);
        }
        Path target = FileMetadataSource.resolve(path).toAbsolutePath();
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + HexFormat.of().formatHex(randomBytes()));
        OptionalLong remembered = OptionalLong.empty();
        boolean worthRecording = current != null && !current.text().equals(text);
        try {
            flushed(temporary, content, target);
            remembered = remember(current, text);
            beforeRename.run(temporary);
            move(temporary, target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            forget(remembered);
            throw e;
        }
        return new Written(FileMetadataSource.sha256(content), !worthRecording || remembered.isPresent());
    }

    private ReloadingMetadataSource.Raw readCurrent() throws IOException {
        try {
            return FileMetadataSource.read(path);
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /** Adds the text about to be replaced; a failure is logged and the save goes on. */
    private OptionalLong remember(ReloadingMetadataSource.Raw replaced, String replacement) {
        if (replaced == null || replaced.text().equals(replacement)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(history.append(replaced.text(), Instant.now()));
        } catch (IOException | RuntimeException e) {
            log.warn("metadata_history_not_written file={} error={}", FileMetadataSource.resolve(path).getFileName(),
                    e.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    private void forget(OptionalLong id) {
        if (id.isEmpty()) {
            return;
        }
        try {
            history.discard(id.getAsLong());
        } catch (IOException | RuntimeException e) {
            log.warn("metadata_history_entry_not_removed file={} error={}",
                    FileMetadataSource.resolve(path).getFileName(), e.getClass().getSimpleName());
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void flushed(Path temporary, byte[] content, Path target) throws IOException {
        try (FileChannel channel = FilePermissions.createOwnerOnly(temporary)) {
            FilePermissions.copyBestEffort(permissions, target, temporary);
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("the folder does not support an atomic rename", e);
        }
        FilePermissions.syncFolder(target.getParent());
    }
}
