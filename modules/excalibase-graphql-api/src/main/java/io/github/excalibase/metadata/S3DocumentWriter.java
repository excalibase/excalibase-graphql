package io.github.excalibase.metadata;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Saves the {@code s3} source with a conditional PUT. The object is read first, to compare its hash with the version
 * the writer started from and to get its ETag; the PUT then quotes that ETag in {@code If-Match} (or sends
 * {@code If-None-Match: *} when there is no object yet), so a change between the read and the write is refused by the
 * store itself. A 412, or the 409 S3 gives to racing conditional writes, is a conflict. The source is writable only
 * when {@link S3ConditionalProbe} has shown the store enforces the header.
 */
final class S3DocumentWriter implements MetadataWriter {

    private final S3DocumentFetcher fetcher;
    private final S3Http http;
    private final URI object;
    private final S3ConditionalProbe probe;

    S3DocumentWriter(S3DocumentFetcher fetcher, S3Http http, URI object, S3ConditionalProbe probe) {
        this.fetcher = fetcher;
        this.http = http;
        this.object = object;
        this.probe = probe;
    }

    @Override
    public boolean writable() {
        return probe.supported();
    }

    @Override
    public String write(String text, String expectedVersion) throws IOException, WriteConflictException {
        Optional<S3DocumentFetcher.Fetched> held = fetcher.fetch();
        String current = held.map(fetched -> fetched.raw().version()).orElse(NO_DOCUMENT);
        if (!current.equals(expectedVersion)) {
            throw new WriteConflictException(current);
        }
        S3Http.Condition condition = held.map(S3DocumentFetcher.Fetched::etag).map(S3Http.Condition::ifMatch)
                .orElseGet(S3Http.Condition::ifNoneMatch);
        if (held.isPresent() && held.get().etag() == null) {
            throw new IOException("the store gave the document no ETag, so it cannot be written conditionally");
        }
        int status = http.put(object, text, condition);
        if (status / 100 == 2) {
            return "sha256:" + FileMetadataSource.sha256(text.getBytes(StandardCharsets.UTF_8));
        }
        if (status == 412 || status == 409 || (status == 404 && held.isPresent())) {
            throw new WriteConflictException(currentVersion());
        }
        throw new IOException("the store answered HTTP " + status + " to the write");
    }

    private String currentVersion() {
        try {
            return fetcher.fetch().map(fetched -> fetched.raw().version()).orElse(NO_DOCUMENT);
        } catch (IOException _) {
            return "unknown";
        }
    }
}
