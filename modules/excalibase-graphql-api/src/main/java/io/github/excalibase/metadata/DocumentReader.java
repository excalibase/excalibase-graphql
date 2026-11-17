package io.github.excalibase.metadata;

/** Turns document text into a validated {@link MetadataDocument}; the reload loop only needs this much. */
@FunctionalInterface
public interface DocumentReader {

    /** @throws MetadataDocumentException the document is refused */
    MetadataDocument parse(String text, long version);
}
