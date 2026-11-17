package io.github.excalibase.metadata;

import java.io.IOException;

/** The one thing a source implements: read the current document text and its version. */
@FunctionalInterface
public interface MetadataLoader {

    /** @throws IOException the document cannot be read right now */
    RawMetadata load() throws IOException;
}
