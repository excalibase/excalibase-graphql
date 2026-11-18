package io.github.excalibase.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The secret that opens the admin API. Only its SHA-256 digest is kept, and the digests are compared in constant
 * time, so neither the content nor the length of the secret shows in how long a wrong guess takes.
 */
public final class AdminSecret {

    public static final int MIN_LENGTH = 32;

    private final byte[] digest;

    private AdminSecret(byte[] digest) {
        this.digest = digest;
    }

    /**
     * @param text the secret as configured; surrounding whitespace is not part of it, since a header cannot carry it
     * @throws IllegalArgumentException the secret is too short or holds a character a header cannot carry; the
     *                                  message never repeats the secret
     */
    public static AdminSecret of(String text) {
        String secret = text == null ? "" : text.strip();
        if (secret.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("must be at least " + MIN_LENGTH + " characters");
        }
        if (!secret.chars().allMatch(AdminSecret::printable)) {
            throw new IllegalArgumentException("may only hold printable ASCII characters, as an HTTP header must carry it");
        }
        return new AdminSecret(sha256(secret));
    }

    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(digest, sha256(candidate));
    }

    private static boolean printable(int character) {
        return character >= 0x20 && character <= 0x7e;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    @Override
    public String toString() {
        return "AdminSecret[redacted]";
    }
}
