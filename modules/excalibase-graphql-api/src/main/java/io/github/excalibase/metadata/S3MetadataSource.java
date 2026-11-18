package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;

/**
 * The {@code s3} source: one object in AWS S3 or an S3-compatible store (MinIO, R2), read with a SigV4-signed GET
 * and polled. It runs on the same reload loop as the file, url and db sources: the version is the SHA-256 of the
 * body, a changed body is judged only when the following poll reads the same bytes again, and a missing object or
 * bucket, an unreachable store and an invalid edit all keep the last good version. Before a first good load every
 * read is refused and the engine reports not ready.
 *
 * <p>A save is a conditional PUT (see {@link S3DocumentWriter}); the source is writable only on a store that has been
 * shown to enforce {@code If-Match}, and read-only otherwise. Credentials are static (settings, files or the AWS
 * environment variables); web-identity and instance-profile credentials are not supported.
 */
public final class S3MetadataSource {

    public static final String NAME = "s3";

    private S3MetadataSource() {
    }

    /** @return the source, not yet started; @throws IllegalStateException when the settings are unusable */
    public static ReloadingMetadataSource create(S3Settings settings, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters,
                                                 boolean envJwtConfigured) {
        URI object = S3Location.require(settings, expectedProjectId);
        if (!settings.credentials().configured()) {
            throw new IllegalStateException("metadata.s3.access-key-id and metadata.s3.secret-access-key are required "
                    + "with metadata.source=s3 (or AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, or a _FILE of each)");
        }
        S3DocumentFetcher fetcher = new S3DocumentFetcher(object, settings);
        S3Http http = new S3Http(settings);
        MetadataWriter writer = new S3DocumentWriter(fetcher, http, object,
                new S3ConditionalProbe(http, object, System::currentTimeMillis));
        return new ReloadingMetadataSource(NAME, fetcher,
                new MetadataDocumentParser(reserved, expectedProjectId, envJwtConfigured)::load, writer,
                settings.pollInterval(), meters);
    }
}
