package io.github.excalibase.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Reports a realtime probe the database failed, once a minute per subscription: every change event of a
 * subscription would otherwise log it. The report names the exception class and the database's SQLState and
 * error code, never the message, which can quote a value from the row being judged.
 */
final class ProbeFailureWarning {

    private static final Logger log = LoggerFactory.getLogger(ProbeFailureWarning.class);
    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final Supplier<Instant> clock;
    private final AtomicReference<Instant> lastReported = new AtomicReference<>();

    ProbeFailureWarning(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** True when this call logged: the first, then at most one a minute. */
    boolean failed(String table, Throwable failure) {
        Instant now = clock.get();
        Instant last = lastReported.get();
        if ((last != null && now.isBefore(last.plus(INTERVAL))) || !lastReported.compareAndSet(last, now)) {
            return false;
        }
        if (log.isWarnEnabled()) {
            log.warn("realtime_probe_failed table={} reason={}", table, reason(failure));
        }
        return true;
    }

    static String reason(Throwable failure) {
        StringBuilder reason = new StringBuilder(failure.getClass().getSimpleName());
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return reason.append(" caused by ").append(sql.getClass().getSimpleName())
                        .append(" sqlstate=").append(sql.getSQLState())
                        .append(" code=").append(sql.getErrorCode()).toString();
            }
        }
        return reason.toString();
    }
}
