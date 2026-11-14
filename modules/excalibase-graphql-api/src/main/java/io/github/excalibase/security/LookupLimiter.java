package io.github.excalibase.security;

import java.util.function.LongSupplier;

/**
 * A token bucket for the lookups of projects not yet resolved: each costs a call to provisioning, and a
 * stream of made-up project ids would otherwise cost one call per request. Resolved projects never ask it.
 */
public final class LookupLimiter {

    private final double permitsPerNano;
    private final double burst;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    public LookupLimiter(int permitsPerSecond, LongSupplier nanoClock) {
        if (permitsPerSecond < 1) {
            throw new IllegalArgumentException("lookup rate must be at least 1 per second");
        }
        this.permitsPerNano = permitsPerSecond / 1_000_000_000.0;
        this.burst = permitsPerSecond * 2.0;
        this.nanoClock = nanoClock;
        this.tokens = burst;
        this.lastRefill = nanoClock.getAsLong();
    }

    public static LookupLimiter perSecond(int permitsPerSecond) {
        return new LookupLimiter(permitsPerSecond, System::nanoTime);
    }

    public synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(burst, tokens + (now - lastRefill) * permitsPerNano);
        lastRefill = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }
}
