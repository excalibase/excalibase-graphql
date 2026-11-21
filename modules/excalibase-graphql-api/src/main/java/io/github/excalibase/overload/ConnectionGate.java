package io.github.excalibase.overload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts open connections against the bound. A connection over it waits, in arrival order, and is let in
 * when another closes, so at most the bound is ever served at once. The queue is bounded too: a connection
 * that finds it full is refused at once, and the owner of a waiting one refuses it when its wait runs out.
 */
final class ConnectionGate implements ConnectionPressure {

    /** A connection waiting for a slot. */
    interface Waiter {
        void admitted();
    }

    /** A keep-alive connection between requests, which can be closed to free its slot. */
    interface IdleConnection {
        void closeIfIdle();
    }

    /** What became of a connection that asked for a slot. */
    enum Outcome { ADMITTED, QUEUED, REFUSED }

    private final int maxConnections;
    private final int maxWaiting;
    private final LongAdder refused = new LongAdder();
    private final Deque<Waiter> waiting = new ArrayDeque<>();
    private final AtomicInteger idleConnections = new AtomicInteger();
    private final Set<IdleConnection> idle = ConcurrentHashMap.newKeySet();
    private final int maxRefusing;
    private int refusing;
    private int open;

    /** The queue holds as many connections as the bound itself, so a flood never grows it without limit. */
    ConnectionGate(int maxConnections) {
        this(maxConnections, maxConnections);
    }

    ConnectionGate(int maxConnections, int maxWaiting) {
        this.maxConnections = maxConnections;
        this.maxWaiting = maxWaiting;
        this.maxRefusing = maxConnections;
    }

    /** ADMITTED took a slot; QUEUED fires {@link Waiter#admitted} later; REFUSED was not queued at all. */
    synchronized Outcome tryAdmit(Waiter waiter) {
        if (open < maxConnections) {
            open++;
            return Outcome.ADMITTED;
        }
        if (waiting.size() >= maxWaiting) {
            return Outcome.REFUSED;
        }
        waiting.addLast(waiter);
        return Outcome.QUEUED;
    }

    /** True when another refusal may be written; refusals in progress are held to the size of the bound. */
    synchronized boolean tryBeginRefusal() {
        if (refusing >= maxRefusing) {
            return false;
        }
        refusing++;
        return true;
    }

    synchronized void endRefusal() {
        refusing--;
    }

    void offerIdle(IdleConnection connection) {
        idle.add(connection);
    }

    void withdrawIdle(IdleConnection connection) {
        idle.remove(connection);
    }

    /** A client is waiting for a slot: free one held by a connection that is only idling between requests. */
    void closeOneIdle() {
        for (IdleConnection connection : idle) {
            if (idle.remove(connection)) {
                connection.closeIfIdle();
                return;
            }
        }
    }

    /** Counts a connection answered with a 503 instead of being served. */
    void recordRefusal() {
        refused.increment();
    }

    long refused() {
        return refused.sum();
    }

    synchronized int waiting() {
        return waiting.size();
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
