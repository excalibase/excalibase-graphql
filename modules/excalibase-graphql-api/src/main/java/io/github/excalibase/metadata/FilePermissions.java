package io.github.excalibase.metadata;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** What the file source's writers do to the files they replace: keep their access rights, make a rename durable. */
final class FilePermissions {

    private FilePermissions() {
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
