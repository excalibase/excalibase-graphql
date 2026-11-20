package io.github.excalibase.overload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts open connections against the bound. A connection over it waits, in arrival order, and is let in
 * when another closes, so at most the bound is ever served at once.
 */
final class ConnectionGate implements ConnectionPressure {

    /** A connection waiting for a slot. */
    interface Waiter {
        void admitted();
    }

    private final int maxConnections;
    private final Deque<Waiter> waiting = new ArrayDeque<>();
    private final AtomicInteger idleConnections = new AtomicInteger();
    private int open;

    ConnectionGate(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /** True when the connection took a slot; otherwise it is queued and {@link Waiter#admitted} fires later. */
    synchronized boolean tryAdmit(Waiter waiter) {
        if (open < maxConnections) {
            open++;
            return true;
        }
        waiting.addLast(waiter);
        return false;
    }

    /** Gives a slot back and hands it to the longest waiting connection. */
    void release() {
        Waiter next;
        synchronized (this) {
            next = waiting.pollFirst();
            if (next == null) {
                open--;
            }
        }
        if (next != null) {
            next.admitted();
        }
    }

    /** A queued connection closed before it was admitted. */
    synchronized void abandon(Waiter waiter) {
        waiting.remove(waiter);
    }

    synchronized int open() {
        return open;
    }

    @Override
    public synchronized boolean isFull() {
        return open >= maxConnections;
    }

    /** Open connections waiting between requests. */
    AtomicInteger idleConnections() {
        return idleConnections;
    }

    int maxConnections() {
        return maxConnections;
    }
}
