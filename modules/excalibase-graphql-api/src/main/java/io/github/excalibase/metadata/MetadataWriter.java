package io.github.excalibase.metadata;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * How a source saves a document: one conditional write. The condition is the source's own version token (the one
 * {@link MetadataSource.Snapshot#sourceVersion()} reports), so a writer that started from an older document is
 * refused instead of overwriting a newer one. A source that cannot offer that guarantee is read-only, never
 * last-write-wins.
 */
public interface MetadataWriter {

    /** The version a writer sends when the source holds no document yet. */
    String NO_DOCUMENT = "none";

    /** Whether a save can succeed right now; false for a read-only source, a read-only folder or a store without conditional writes. */
    boolean writable();

    /**
     * Replaces the document when the source still holds {@code expectedVersion}.
     *
     * @return the new version token, as a later read of the source reports it
     * @throws WriteConflictException the source holds another version
     * @throws IOException            the write failed; nothing was replaced
     */
    String write(String text, String expectedVersion) throws IOException, WriteConflictException;

    /** A source that is not saved to, such as {@code url}; {@code writable} lets a test still say otherwise. */
    static MetadataWriter readOnly(BooleanSupplier writable) {
        return new MetadataWriter() {
            @Override
            public boolean writable() {
                return writable.getAsBoolean();
            }

            @Override
            public String write(String text, String expectedVersion) throws IOException {
                throw new IOException("this source is read-only");
            }
        };
    }
}
