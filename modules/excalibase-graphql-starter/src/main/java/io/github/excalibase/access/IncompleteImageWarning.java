package io.github.excalibase.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Reports a change image withheld because it lacks columns needed to judge or deliver it: the table
 * does not publish complete old rows (no REPLICA IDENTITY FULL). Once a minute per table, since every
 * change of such a table is withheld.
 */
final class IncompleteImageWarning {

    static final IncompleteImageWarning SHARED = new IncompleteImageWarning(Instant::now);

    private static final Logger log = LoggerFactory.getLogger(IncompleteImageWarning.class);
    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final Supplier<Instant> clock;
    private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();

    IncompleteImageWarning(Supplier<Instant> clock) {
        this.clock = clock;
    }

    void withheld(String table, Collection<String> missing) {
        Instant now = clock.get();
        AtomicBoolean due = new AtomicBoolean();
        lastReported.compute(table, (key, last) -> {
            if (last != null && now.isBefore(last.plus(INTERVAL))) {
                return last;
            }
            due.set(true);
            return now;
        });
        if (due.get()) {
            log.warn("realtime_image_incomplete table={} missing={}", table, missing);
        }
    }
}
