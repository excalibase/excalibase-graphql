package io.github.excalibase.admin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Limits "test a token" per address, in fixed windows. It counts every check, right or wrong, and nothing resets it:
 * {@link AdminGuard} forgets an address's failures when it sends the right admin secret, so a checker that shared it
 * would be an unlimited oracle for anyone who holds that secret.
 */
public final class TokenCheckLimiter {

    private static final class Window {
        private final long startedAt;
        private int count;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final int maxChecks;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Map<String, Window> windows;

    public TokenCheckLimiter(int maxChecks, Duration window, int maxTracked, LongSupplier nanoClock) {
        if (maxChecks < 1 || maxTracked < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("the check limit, the window and the tracked addresses must be positive");
        }
        this.maxChecks = maxChecks;
        this.windowNanos = window.toNanos();
        this.nanoClock = nanoClock;
        this.windows = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > maxTracked;
            }
        };
    }

    /** Counts one check; false when the address has used its checks for this window. A refusal is not counted. */
    public synchronized boolean tryAcquire(String address) {
        long now = nanoClock.getAsLong();
        Window window = windows.get(address);
        if (window == null || now - window.startedAt >= windowNanos) {
            window = new Window(now);
            windows.put(address, window);
        }
        if (window.count >= maxChecks) {
            return false;
        }
        window.count++;
        return true;
    }

    /** Seconds until the address's window ends, for {@code Retry-After}. */
    public synchronized long retryAfterSeconds(String address) {
        Window window = windows.get(address);
        if (window == null) {
            return 1;
        }
        long remaining = Math.max(0, window.startedAt + windowNanos - nanoClock.getAsLong());
        return Math.max(1, (remaining + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }

    public synchronized int tracked() {
        return windows.size();
    }
}
