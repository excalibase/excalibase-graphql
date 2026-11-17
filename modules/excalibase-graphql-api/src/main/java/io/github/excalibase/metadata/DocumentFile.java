package io.github.excalibase.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Reading a document from disk, one way for the engine's file source and for {@code excalibase validate}:
 * a folder means {@value FileMetadataSource#FILE_NAME} inside it, the file is opened by its path on every read
 * (so a swapped symlink is followed), and at most one byte over the limit is read.
 */
final class DocumentFile {

    private DocumentFile() {
    }

    static Path resolve(Path path) {
        return Files.isDirectory(path) ? path.resolve(FileMetadataSource.FILE_NAME) : path;
    }

    /** @throws CharacterCodingException the file is not UTF-8 text */
    static RawMetadata read(Path path) throws IOException {
        Path file = resolve(path);
        byte[] bytes;
        try {
            bytes = readCapped(file);
        } catch (NoSuchFileException e) {
            // A swap may remove the old target between resolving the link and opening it; once more is enough.
            bytes = readCapped(file);
        }
        return new RawMetadata(decode(bytes), ContentHash.of(bytes));
    }

    private static byte[] readCapped(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(MetadataDocumentParser.MAX_BYTES + 1);
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
