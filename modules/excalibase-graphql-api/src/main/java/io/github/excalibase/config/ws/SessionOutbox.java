package io.github.excalibase.config.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delivers one session's changes, in order, off the thread that publishes them. The queue is bounded and each
 * delivery is timed: a client that does not read (so a send blocks past the grace, or the queue fills behind
 * it) is closed ({@link WsClose#SLOW_CONSUMER}) instead of growing memory or holding up anyone else's events.
 */
final class SessionOutbox {

    private static final Logger log = LoggerFactory.getLogger(SessionOutbox.class);

    private final WebSocketSession session;
    private final WsRuntime runtime;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final int capacity;
    private final long slowConsumerNanos;

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;

    SessionOutbox(WebSocketSession session, WsRuntime runtime, Executor executor, ScheduledExecutorService scheduler,
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
            synchronized (this) {
                work = closed ? null : queue.poll();
                if (work == null) {
                    draining = false;
                    return;
                }
            }
            run(work);
        }
    }

    /** One delivery, with a timer that closes the session if the send does not come back within the grace. */
    private void run(Runnable work) {
        ScheduledFuture<?> watchdog = scheduler.schedule(() -> {
            close();
            runtime.close(session, WsClose.SLOW_CONSUMER);
        }, slowConsumerNanos, TimeUnit.NANOSECONDS);
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.warn("Delivery to session {} failed: {}", session.getId(), failure.toString());
        } finally {
            watchdog.cancel(false);
        }
    }
}
