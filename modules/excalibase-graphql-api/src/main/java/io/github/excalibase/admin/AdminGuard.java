package io.github.excalibase.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Decides whether a request on the admin port may proceed. A blocked address is refused before the secret is
 * looked at, so guessing cannot go on while blocked; a missing secret counts as a failure like a wrong one.
 */
public final class AdminGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    /** The header that carries the secret. */
    public static final String SECRET_HEADER = "X-Excalibase-Admin-Secret";

    public enum Verdict { ALLOWED, UNAUTHORIZED, LIMITED }

    private final Secret secret;
    private final FailedAttempts attempts;

    public AdminGuard(Secret secret, FailedAttempts attempts) {
        this.secret = secret;
        this.attempts = attempts;
    }

    /** @param presented the header value, or null when the request has none; never logged */
    public Verdict check(String address, String presented) {
        if (attempts.blocked(address)) {
            return Verdict.LIMITED;
        }
        if (secret.matches(presented)) {
            attempts.success(address);
            return Verdict.ALLOWED;
        }
        attempts.failure(address);
        Duration blockedFor = attempts.blockedFor(address);
        if (!blockedFor.isZero()) {
            log.warn("admin_address_blocked address={} seconds={}", address, blockedFor.toSeconds() + 1);
        }
        return Verdict.UNAUTHORIZED;
    }

    /** Seconds until a blocked address may try again, for {@code Retry-After}. */
    public long retryAfterSeconds(String address) {
        return attempts.blockedFor(address).toSeconds() + 1;
    }

    @Override
    public String toString() {
        return "AdminGuard[" + secret + "]";
    }

    public static final class Secret {

        public static final int MIN_LENGTH = 32;

        private final byte[] digest;

        private Secret(byte[] digest) {
            this.digest = digest;
        }

        /**
         * @param text the secret as configured; surrounding whitespace is not part of it, since a header cannot carry it
         * @throws IllegalArgumentException the secret is too short or holds a character a header cannot carry; the
         *                                  message never repeats the secret
         */
        public static Secret of(String text) {
            String secret = text == null ? "" : text.strip();
            if (secret.length() < MIN_LENGTH) {
                throw new IllegalArgumentException("must be at least " + MIN_LENGTH + " characters");
            }
            if (!secret.chars().allMatch(Secret::printable)) {
                throw new IllegalArgumentException("may only hold printable ASCII characters, as an HTTP header must carry it");
            }
            return new Secret(sha256(secret));
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
            return "Secret[redacted]";
        }
    }

    public static final class FailedAttempts {

        private static final class Failures {
            private final long firstAt;
            private int count;

            private Failures(long firstAt) {
                this.firstAt = firstAt;
            }
        }

        private final int maxFailures;
        private final long windowNanos;
        private final LongSupplier nanoClock;
        private final Map<String, Failures> records;

        public FailedAttempts(int maxFailures, Duration window, int maxTracked, LongSupplier nanoClock) {
            if (maxFailures < 1 || maxTracked < 1 || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("the failure limit, the window and the tracked addresses must be positive");
            }
            this.maxFailures = maxFailures;
            this.windowNanos = window.toNanos();
            this.nanoClock = nanoClock;
            this.records = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Failures> eldest) {
                    return size() > maxTracked;
                }
            };
        }

        /** How long the address stays blocked; zero when it is not. */
        public synchronized Duration blockedFor(String address) {
            Failures seen = live(address);
            if (seen == null || seen.count < maxFailures) {
                return Duration.ZERO;
            }
            return Duration.ofNanos(Math.max(1, seen.firstAt + windowNanos - nanoClock.getAsLong()));
        }

        public boolean blocked(String address) {
            return !blockedFor(address).isZero();
        }

        public synchronized void failure(String address) {
            Failures seen = live(address);
            if (seen == null) {
                seen = new Failures(nanoClock.getAsLong());
                records.put(address, seen);
            }
            seen.count++;
        }

        public synchronized void success(String address) {
            records.remove(address);
        }

        public synchronized int tracked() {
            return records.size();
        }

        /** The address's record while its window is open; an expired one is dropped. */
        private Failures live(String address) {
            Failures seen = records.get(address);
            if (seen != null && nanoClock.getAsLong() - seen.firstAt > windowNanos) {
                records.remove(address);
                return null;
            }
            return seen;
        }
    }
}
