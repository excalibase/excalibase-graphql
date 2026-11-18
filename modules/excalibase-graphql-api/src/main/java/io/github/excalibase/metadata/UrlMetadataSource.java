package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.TokenFileSource;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;
import java.util.function.LongSupplier;

/**
 * The {@code url} source: one document served over HTTP(S), polled. It is read-only, so the console can only
 * export it. It runs on the same reload loop as the file source: the last good version survives timeouts, 5xx and
 * bad documents, nothing is served before a first good load, and a changed version (the hash of the body; ETag
 * and 304 are not used, see {@link UrlDocumentFetcher}) is judged only when a following poll reads the same body
 * again, so a document being replaced mid-flight is not believed on first sight.
 */
public final class UrlMetadataSource {

    public static final String NAME = "url";

    private UrlMetadataSource() {
    }

    /** @return the source, not yet started; @throws IllegalStateException when the location is unusable */
    public static ReloadingMetadataSource create(UrlSettings settings, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters,
                                                 boolean envJwtConfigured) {
        return create(settings, reserved, expectedProjectId, meters, System::currentTimeMillis, envJwtConfigured);
    }

    /** The clock drives the token file's re-read throttle; a test moves it instead of waiting. */
    static ReloadingMetadataSource create(UrlSettings settings, ReservedSchemas reserved, String expectedProjectId,
                                          MeterRegistry meters, LongSupplier clock, boolean envJwtConfigured) {
        URI location = UrlLocation.require(settings.location(), expectedProjectId, settings.allowInsecure());
        TokenFileSource token = hasToken(settings)
                ? new TokenFileSource(settings.tokenFile(), settings.token(), clock) : null;
        UrlDocumentFetcher fetcher = new UrlDocumentFetcher(location, token, settings.connectTimeout(),
                settings.readTimeout());
        return new ReloadingMetadataSource(NAME, fetcher,
                new MetadataDocumentParser(reserved, expectedProjectId, envJwtConfigured)::load, () -> false,
                settings.pollInterval(), meters);
    }

    private static boolean hasToken(UrlSettings settings) {
        return !isBlank(settings.token()) || !isBlank(settings.tokenFile());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
