package io.github.excalibase.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Decides whether a request on the admin port may proceed. A blocked address is refused before the secret is
 * looked at, so guessing cannot go on while blocked; a missing secret counts as a failure like a wrong one.
 */
public final class AdminGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    /** The header that carries the secret. */
    public static final String SECRET_HEADER = "X-Excalibase-Admin-Secret";

    public enum Verdict { ALLOWED, UNAUTHORIZED, LIMITED }

    private final AdminSecret secret;
    private final FailedAttempts attempts;

    public AdminGuard(AdminSecret secret, FailedAttempts attempts) {
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
}
