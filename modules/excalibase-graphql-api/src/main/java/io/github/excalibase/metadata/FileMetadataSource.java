package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The {@code file} source: one document on disk, read at start and then by polling its content hash. Polling,
 * not inotify, because a Kubernetes ConfigMap mount changes by swapping the {@code ..data} symlink, which
 * inotify on the file misses. The file is opened by its configured path on every poll, so the link is
 * resolved afresh and a swap is seen as ordinary new content. {@code excalibase validate} reads files the same way.
 * A save (see {@link FileDocumentWriter}) is a conditional atomic replace; a read-only folder makes the source read-only.
 */
public final class FileMetadataSource {

    public static final String NAME = "file";
    public static final String FILE_NAME = "permissions.yaml";

    private FileMetadataSource() {
    }

    /** @param path the document, or a folder holding {@value #FILE_NAME} (a mounted ConfigMap) */
    public static ReloadingMetadataSource create(Path path, Duration interval, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters,
                                                 boolean envJwtConfigured) {
        return create(path, interval, reserved, expectedProjectId, meters, envJwtConfigured,
                new FileDocumentWriter(path));
    }

    static ReloadingMetadataSource create(Path path, Duration interval, ReservedSchemas reserved,
                                          String expectedProjectId, MeterRegistry meters, boolean envJwtConfigured,
                                          FileDocumentWriter writer) {
        return new ReloadingMetadataSource(NAME, () -> read(path),
                new MetadataDocumentParser(reserved, expectedProjectId, envJwtConfigured)::load, writer,
                interval, ReloadingMetadataSource.DEFAULT_SETTLE, meters);
    }

    static Path resolve(Path path) {
        return Files.isDirectory(path) ? path.resolve(FILE_NAME) : path;
    }

    /** At most one byte over the limit is read. A file that is not UTF-8 text fails with a character-coding IOException. */
    static ReloadingMetadataSource.Raw read(Path path) throws IOException {
        Path file = resolve(path);
        byte[] bytes;
        try {
            bytes = readCapped(file);
        } catch (NoSuchFileException _) {
            // A swap may remove the old target between resolving the link and opening it; once more is enough.
            bytes = readCapped(file);
        }
        return new ReloadingMetadataSource.Raw(decodeUtf8(bytes), sha256(bytes));
    }

    /** @throws IOException (a CharacterCodingException) when the bytes are not UTF-8 text */
    static String decodeUtf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] readCapped(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(MetadataDocumentParser.MAX_BYTES + 1);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
