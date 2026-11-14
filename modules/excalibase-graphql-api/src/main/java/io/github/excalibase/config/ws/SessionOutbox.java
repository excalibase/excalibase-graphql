package io.github.excalibase.config.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Delivers one session's changes, in order, off the thread that publishes them. The queue is bounded and the
 * session's send buffer is watched: a client that does not read is closed ({@link WsClose#SLOW_CONSUMER}) instead
 * of growing memory or holding up anyone else's events.
 */
final class SessionOutbox {

    private static final Logger log = LoggerFactory.getLogger(SessionOutbox.class);
    private static final long RETRY_MILLIS = 100;

    private final WsSession session;
    private final WsRuntime runtime;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final int capacity;
    private final long slowConsumerNanos;

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;
    private long blockedSince;

    SessionOutbox(WsSession session, WsRuntime runtime, Executor executor, ScheduledExecutorService scheduler,
                  WsLimits limits) {
        this.session = session;
        this.runtime = runtime;
        this.executor = executor;
        this.scheduler = scheduler;
        this.capacity = limits.outboxCapacity();
        this.slowConsumerNanos = limits.slowConsumer().toNanos();
    }

    /** Queues one piece of delivery work; it never blocks the caller. */
    void submit(Runnable work) {
        boolean start = false;
        boolean overflow = false;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (queue.size() >= capacity) {
                closed = true;
                queue.clear();
                overflow = true;
            } else {
                queue.add(work);
                start = !draining;
                draining = draining || start;
            }
        }
        if (overflow) {
            runtime.close(session, WsClose.SLOW_CONSUMER);
        } else if (start) {
            executor.execute(this::drain);
        }
    }

    synchronized void close() {
        closed = true;
        queue.clear();
    }

    private void drain() {
        while (true) {
            Runnable work;
            boolean slow = false;
            synchronized (this) {
                if (closed || queue.isEmpty()) {
                    draining = false;
                    return;
                }
                if (session.isBackedUp()) {
                    long now = System.nanoTime();
                    blockedSince = blockedSince == 0 ? now : blockedSince;
                    if (now - blockedSince > slowConsumerNanos) {
                        closed = true;
                        queue.clear();
                        draining = false;
                        slow = true;
                    }
                    work = null;
                } else {
                    blockedSince = 0;
                    work = queue.poll();
                }
            }
            if (slow) {
                runtime.close(session, WsClose.SLOW_CONSUMER);
                return;
            }
            if (work == null) {
                scheduler.schedule(() -> executor.execute(this::drain), RETRY_MILLIS, TimeUnit.MILLISECONDS);
                return;
            }
            run(work);
        }
    }

    private void run(Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.warn("Delivery to session {} failed: {}", session.getId(), failure.toString());
        }
    }
}
