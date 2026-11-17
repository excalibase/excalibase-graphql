package io.github.excalibase.metadata;

import io.github.excalibase.schema.ReservedSchemas;
import io.github.excalibase.security.TokenFileSource;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;

/**
 * The {@code url} source: one document served over HTTP(S), polled. It is read-only, so the console can only
 * export it. It runs on the same reload loop as the file source: the last good version survives timeouts, 5xx and
 * bad documents, nothing is served before a first good load, and a changed version (ETag, else a body hash) is
 * judged only when a following poll sees it again. For HTTP that second look is cheap (a 304 or a repeated hash)
 * and it also stops a document that is being replaced mid-flight from being believed on first sight.
 */
public final class UrlMetadataSource {

    public static final String NAME = "url";

    private UrlMetadataSource() {
    }

    /** @return the source, not yet started; @throws IllegalStateException when the location is unusable */
    public static ReloadingMetadataSource create(UrlSettings settings, ReservedSchemas reserved,
                                                 String expectedProjectId, MeterRegistry meters) {
        URI location = UrlLocation.require(settings.location(), expectedProjectId, settings.allowInsecure());
        TokenFileSource token = hasToken(settings)
                ? new TokenFileSource(settings.tokenFile(), settings.token()) : null;
        UrlDocumentFetcher fetcher = new UrlDocumentFetcher(location, token, settings.connectTimeout(),
                settings.readTimeout());
        return new ReloadingMetadataSource(NAME, fetcher,
                new MetadataDocumentParser(reserved, expectedProjectId)::parse, () -> false,
                settings.pollInterval(), meters);
    }

    private static boolean hasToken(UrlSettings settings) {
        return !isBlank(settings.token()) || !isBlank(settings.tokenFile());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
