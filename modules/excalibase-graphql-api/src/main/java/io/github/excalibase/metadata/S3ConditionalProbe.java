package io.github.excalibase.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.LongSupplier;

/**
 * Proves a store enforces {@code If-Match} on PUT before the engine offers to save to it: some S3-compatible stores
 * accept the header and ignore it, and saving there would be last-write-wins. The probe puts a scratch object next
 * to the document, puts it again with an ETag that cannot match and again with {@code If-None-Match: *}, and expects 412 each time. Only an answer that says "not
 * supported" is kept for good; a store that cannot be reached, or credentials that may not write, are tried again
 * after a pause, so a store that is down at start does not stay read-only. The scratch object is deleted.
 */
final class S3ConditionalProbe {

    private static final Logger log = LoggerFactory.getLogger(S3ConditionalProbe.class);
    static final long RETRY_MILLIS = 30_000;
    private static final String NEVER_AN_ETAG = "\"00000000000000000000000000000000\"";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final S3Http http;
    private final URI document;
    private final LongSupplier clock;
    private Boolean verdict;
    private long lastAttempt = Long.MIN_VALUE;

    S3ConditionalProbe(S3Http http, URI document, LongSupplier clock) {
        this.http = http;
        this.document = document;
        this.clock = clock;
    }

    /** True only once the store has refused a mismatching {@code If-Match}. */
    synchronized boolean supported() {
        if (verdict != null) {
            return verdict;
        }
        long now = clock.getAsLong();
        if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < RETRY_MILLIS) {
            return false;
        }
        lastAttempt = now;
        try {
            verdict = run();
            log.info("metadata_s3_conditional_writes supported={}", verdict);
            return verdict;
        } catch (IOException e) {
            log.warn("metadata_s3_conditional_writes_unproven error={}", e.getMessage());
            return false;
        }
    }

    private boolean run() throws IOException {
        byte[] id = new byte[8];
        RANDOM.nextBytes(id);
        URI scratch = URI.create(document + ".probe-" + HexFormat.of().formatHex(id));
        int created = http.put(scratch, "probe", null);
        if (created / 100 != 2) {
            throw new IOException("the store answered HTTP " + created + " to a write: the credentials may not write");
        }
        try {
            return refusesMismatch(scratch, S3Http.Condition.ifMatch(NEVER_AN_ETAG))
                    && refusesMismatch(scratch, S3Http.Condition.ifNoneMatch());
        } finally {
            removed(scratch);
        }
    }

    /** A first save relies on {@code If-None-Match: *}, every other on {@code If-Match}; both must be enforced. */
    private boolean refusesMismatch(URI scratch, S3Http.Condition condition) throws IOException {
        int status = http.put(scratch, "probe", condition);
        return switch (status) {
            case 412 -> true;
            case 200, 204, 400, 501 -> false;
            default -> throw new IOException("the store answered HTTP " + status + " to a conditional write");
        };
    }

    private void removed(URI scratch) {
        try {
            http.delete(scratch);
        } catch (IOException e) {
            log.warn("metadata_s3_probe_object_left error={}", e.getMessage());
        }
    }
}
