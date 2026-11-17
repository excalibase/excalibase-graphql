package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The {@code file} source: one document on disk, read at start and then by polling its content hash. Polling,
 * not inotify, because a Kubernetes ConfigMap mount changes by swapping the {@code ..data} symlink, which
 * inotify on the file misses. The file is opened by its configured path on every poll, so the link is
 * resolved afresh and a swap is seen as ordinary new content.
 */
public final class FileMetadataSource {

    public static final String NAME = "file";
    public static final String FILE_NAME = "permissions.yaml";

    private FileMetadataSource() {
    }

    /** @param path the document, or a folder holding {@value #FILE_NAME} (a mounted ConfigMap) */
    public static ReloadingMetadataSource create(Path path, Duration interval, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters) {
        return new ReloadingMetadataSource(NAME, () -> DocumentFile.read(path),
                new MetadataDocumentParser(reserved, expectedProjectId), () -> writable(path), interval, meters);
    }

    private static boolean writable(Path path) {
        Path folder = DocumentFile.resolve(path).toAbsolutePath().getParent();
        return folder != null && Files.isDirectory(folder) && Files.isWritable(folder);
    }
}
