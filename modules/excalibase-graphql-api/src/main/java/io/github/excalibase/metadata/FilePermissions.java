package io.github.excalibase.metadata;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** What the file source's writers do to the files they replace: keep their access rights, make a rename durable. */
final class FilePermissions {

    private FilePermissions() {
    }

    /**
     * A new file open for writing that only its owner can read, so a mode that is then not copied errs on the safe side.
     * A platform without POSIX permissions makes it an ordinary new file.
     */
    static FileChannel createOwnerOnly(Path file) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException _) {
            return FileChannel.open(file, options);
        }
    }

    /** How a writer gives a new file the permissions of the one it replaces; a test swaps it for one that fails. */
    @FunctionalInterface
    interface Copier {
        void copy(Path model, Path target) throws IOException;
    }

    /** A copy that must never fail a save: a refusal is logged and the file keeps the permissions it was made with. */
    static void copyBestEffort(Copier copier, Path model, Path target) {
        try {
            copier.copy(model, target);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(FilePermissions.class).warn("metadata_permissions_not_copied file={} error={}",
                    target.getFileName(), e.getClass().getSimpleName());
        }
    }

    /** Gives {@code target} the permissions of {@code model}; nothing happens when there is no model or no POSIX. */
    static void copy(Path model, Path target) throws IOException {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(model);
        } catch (NoSuchFileException | UnsupportedOperationException _) {
            return;
        }
        Files.setPosixFilePermissions(target, permissions);
    }

    /** Makes a rename durable; a platform that cannot open a folder for reading skips it. */
    static void syncFolder(Path folder) {
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException _) {
            // the rename is already atomic; only its durability across power loss is best effort
        }
    }
}
