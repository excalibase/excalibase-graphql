package io.github.excalibase.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/**
 * Saves a document so a reader sees the old file or the new one, never a half-written one: it writes a temp
 * file in the same folder (a rename across folders is not atomic), syncs it, then renames it over the target.
 * A save that names the version it started from is refused when the file has moved on. The check and the rename
 * are not one step, so this is safe for a single writer, which is what a mounted file is.
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * @param expectedVersion the content hash the caller read, or null to overwrite whatever is there
     * @return the hash of what was written
     * @throws MetadataConflictException the file is not at {@code expectedVersion}
     */
    public static String write(Path target, String content, String expectedVersion) throws IOException {
        if (expectedVersion != null && !expectedVersion.equals(currentVersion(target))) {
            throw new MetadataConflictException("the file changed since version " + expectedVersion);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path folder = target.toAbsolutePath().getParent();
        Path temp = folder.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
            Files.move(temp, target, ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return ContentHash.of(bytes);
    }

    private static String currentVersion(Path target) throws IOException {
        try {
            return ContentHash.of(Files.readAllBytes(target));
        } catch (NoSuchFileException e) {
            return null;
        }
    }
}
