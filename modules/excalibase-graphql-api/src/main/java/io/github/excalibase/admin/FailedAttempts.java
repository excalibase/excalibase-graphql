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

    private static final class Record {
        private final long firstAt;
        private int failures;

        private Record(long firstAt) {
            this.firstAt = firstAt;
        }
    }

    private final int maxFailures;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Map<String, Record> records;

    public FailedAttempts(int maxFailures, Duration window, int maxTracked, LongSupplier nanoClock) {
        if (maxFailures < 1 || maxTracked < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("the failure limit, the window and the tracked addresses must be positive");
        }
        this.maxFailures = maxFailures;
        this.windowNanos = window.toNanos();
        this.nanoClock = nanoClock;
        this.records = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Record> eldest) {
                return size() > maxTracked;
            }
        };
    }

    /** How long the address stays blocked; zero when it is not. */
    public synchronized Duration blockedFor(String address) {
        Record record = live(address);
        if (record == null || record.failures < maxFailures) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(Math.max(1, record.firstAt + windowNanos - nanoClock.getAsLong()));
    }

    public boolean blocked(String address) {
        return !blockedFor(address).isZero();
    }

    public synchronized void failure(String address) {
        Record record = live(address);
        if (record == null) {
            record = new Record(nanoClock.getAsLong());
            records.put(address, record);
        }
        record.failures++;
    }

    public synchronized void success(String address) {
        records.remove(address);
    }

    public synchronized int tracked() {
        return records.size();
    }

    /** The address's record while its window is open; an expired one is dropped. */
    private Record live(String address) {
        Record record = records.get(address);
        if (record != null && nanoClock.getAsLong() - record.firstAt > windowNanos) {
            records.remove(address);
            return null;
        }
        return record;
    }
}
