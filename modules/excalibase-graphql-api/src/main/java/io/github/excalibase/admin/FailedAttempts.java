package io.github.excalibase.admin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Failed admin attempts per address. An address that fails {@code maxFailures} times inside the window is blocked
 * until the window since its first failure has passed. At most {@code maxTracked} addresses are remembered: the
 * least recently seen is dropped first, so a flood of addresses cannot grow memory, and an address still blocked
 * is touched on every check and so outlives quiet ones.
 */
public final class FailedAttempts {

    private static final class Failures {
        private final long firstAt;
        private int failures;

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
        if (seen == null || seen.failures < maxFailures) {
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
        seen.failures++;
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
