package io.github.excalibase.nosql.compiler;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Opaque base64 cursor encoding for keyset pagination.
 * Payload: (createdAt ISO-8601, id UUID) separated by '|'.
 * Opaque to clients — the format can change without breaking callers that
 * treat the cursor as a blob.
 */
public final class CursorCodec {

    private CursorCodec() {}

    public record Cursor(Instant createdAt, String id) {}

    public static String encode(Instant createdAt, String id) {
        String payload = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must be a non-empty string");
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor is not valid base64-url", e);
        }
        int sep = payload.indexOf('|');
        if (sep <= 0 || sep == payload.length() - 1) {
            throw new IllegalArgumentException("cursor payload malformed");
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(payload.substring(0, sep));
        } catch (Exception e) {
            throw new IllegalArgumentException("cursor createdAt not ISO-8601", e);
        }
        return new Cursor(createdAt, payload.substring(sep + 1));
    }
}
