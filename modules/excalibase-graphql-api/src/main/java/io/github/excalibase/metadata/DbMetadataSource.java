package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code db} source: the document is the newest row of {@value DbDocumentLoader#TABLE}, in the served
 * database or, with {@code METADATA_DATABASE_URL}, in a separate one. It runs on the same reload loop as the
 * file and url sources, polled: the version is the SHA-256 of the document text, and a changed document is judged
 * only when the following poll reads the same text again, so an edit seen halfway is not believed. A missing
 * table or row, an unreachable database and an invalid edit all keep the last good version, and before a first
 * good one every read is refused and the engine reports not ready.
 *
 * <p>A save is a compare-and-set that adds the next row (see {@link DbDocumentWriter}), so the history stays.
 * Postgres only.
 */
public final class DbMetadataSource {

    public static final String NAME = "db";

    private DbMetadataSource() {
    }

    /** @return the source, not yet started; @throws IllegalStateException when the settings are unusable */
    public static ReloadingMetadataSource create(DbSettings settings, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters,
                                                 boolean envJwtConfigured) {
        return create(settings, reserved, expectedProjectId, meters, EngineAuth.of(envJwtConfigured));
    }

    public static ReloadingMetadataSource create(DbSettings settings, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters,
                                                 EngineAuth engineAuth) {
        DbDocumentLoader loader = new DbDocumentLoader(settings);
        return new ReloadingMetadataSource(NAME, loader,
                new MetadataDocumentParser(reserved, expectedProjectId, engineAuth)::load,
                new DbDocumentWriter(loader), settings.pollInterval(), meters);
    }
}
