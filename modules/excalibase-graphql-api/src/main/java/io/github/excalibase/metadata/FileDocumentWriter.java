package io.github.excalibase.metadata;

import java.io.IOException;
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
import java.util.HexFormat;

/**
 * Saves the {@code file} source: the new text goes to {@code permissions.yaml.tmp-<random>} in the same folder, is
 * flushed to disk, and is renamed over the document, so a reader (or a crash) sees the old file or the new one, never
 * a part of it. The version check and the rename happen under one lock, which makes saves through this process
 * conditional; two replicas sharing one writable file are not guarded against each other, so use {@code db} or
 * {@code s3} for that.
 */
final class FileDocumentWriter implements MetadataWriter {

    /** Runs after the temporary file is complete and before the rename; a test uses it to simulate a crash. */
    @FunctionalInterface
    interface BeforeRename {
        void run(Path temporary) throws IOException;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path path;
    private final BeforeRename beforeRename;

    FileDocumentWriter(Path path) {
        this(path, _ -> { });
    }

    FileDocumentWriter(Path path, BeforeRename beforeRename) {
        this.path = path;
        this.beforeRename = beforeRename;
    }

    @Override
    public boolean writable() {
        Path folder = FileMetadataSource.resolve(path).toAbsolutePath().getParent();
        return folder != null && Files.isDirectory(folder) && Files.isWritable(folder);
    }

    @Override
    public synchronized String write(String text, String expectedVersion) throws IOException, WriteConflictException {
        String current = currentVersion();
        if (!current.equals(expectedVersion)) {
            throw new WriteConflictException(current);
        }
        Path target = FileMetadataSource.resolve(path).toAbsolutePath();
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + HexFormat.of().formatHex(randomBytes()));
        try {
            flushed(temporary, content);
            beforeRename.run(temporary);
            move(temporary, target);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return FileMetadataSource.sha256(content);
    }

    private String currentVersion() throws IOException {
        try {
            return FileMetadataSource.read(path).version();
        } catch (NoSuchFileException _) {
            return NO_DOCUMENT;
        }
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void flushed(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
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
        syncFolder(target.getParent());
    }

    /** Makes the rename durable; a platform that cannot open a folder for reading skips it. */
    private static void syncFolder(Path folder) {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException _) {
            // the rename is already atomic; only its durability across power loss is best effort
        }
    }
}
