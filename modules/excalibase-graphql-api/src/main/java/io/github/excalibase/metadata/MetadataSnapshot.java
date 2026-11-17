package io.github.excalibase.metadata;

import java.time.Instant;

/**
 * One accepted version of the document.
 *
 * @param version       the local counter, up by one on every accepted swap; it feeds the engine's change detection
 * @param sourceVersion what the source calls this version (a content hash for the file source)
 */
public record MetadataSnapshot(MetadataDocument document, long version, String sourceVersion, Instant loadedAt) {
}
