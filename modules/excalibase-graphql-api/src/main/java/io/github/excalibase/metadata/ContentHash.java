package io.github.excalibase.metadata;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of a document's bytes, the version of a source that has no version of its own. */
final class ContentHash {

    private ContentHash() {
    }

    static String of(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
