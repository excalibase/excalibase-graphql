package io.github.excalibase.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A {@link LookupLimiter} for each client address, so one client asking for made-up projects spends its own
 * allowance and not the one every other client needs. The clients held are bounded, the least recently seen
 * going first; a client dropped starts again with a full allowance, which a flood of addresses can only use
 * to get what a single address already gets.
 */
public final class ClientLookups {

    private final int permitsPerSecond;
    private final LongSupplier nanoClock;
    private final Map<String, LookupLimiter> limiters;

    public ClientLookups(int permitsPerSecond, int maxClients, LongSupplier nanoClock) {
        if (maxClients < 1) {
            throw new IllegalArgumentException("clients held must be at least 1");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.nanoClock = nanoClock;
        this.limiters = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LookupLimiter> eldest) {
                return size() > maxClients;
            }
        };
    }

    public static ClientLookups perSecond(int permitsPerSecond, int maxClients) {
        return new ClientLookups(permitsPerSecond, maxClients, System::nanoTime);
    }

    public boolean tryAcquire(String client) {
        LookupLimiter limiter;
        synchronized (limiters) {
            limiter = limiters.computeIfAbsent(client, key -> new LookupLimiter(permitsPerSecond, nanoClock));
        }
        return limiter.tryAcquire();
    }

    int clientsHeld() {
        synchronized (limiters) {
            return limiters.size();
        }
    }
}
