package io.github.excalibase.overload;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Caps concurrent requests; a caller over the cap waits a short bounded time before giving up. */
public class InFlightLimiter {

    private final Semaphore permits;
    private final int capacity;
    private final Duration maxWait;

    public InFlightLimiter(int capacity, Duration maxWait) {
        if (capacity < 1) {
            throw new IllegalArgumentException("in-flight cap must be at least 1, got " + capacity);
        }
        this.capacity = capacity;
        this.maxWait = maxWait;
        this.permits = new Semaphore(capacity);
    }

    public static InFlightLimiter forCpus(int cpus, int perCpu, Duration maxWait) {
        return new InFlightLimiter(cpus * perCpu, maxWait);
    }

    public int capacity() {
        return capacity;
    }

    public boolean tryAcquire() throws InterruptedException {
        return permits.tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void release() {
        permits.release();
    }
}
