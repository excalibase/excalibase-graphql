package io.github.excalibase.access;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A table whose change images lack columns is reported once a minute, not once per change. */
class IncompleteImageWarningTest {

    private final Logger warningLog = (Logger) LoggerFactory.getLogger(IncompleteImageWarning.class);
    private final ListAppender<ILoggingEvent> warnings = new ListAppender<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));
    private final IncompleteImageWarning warning = new IncompleteImageWarning(now::get);

    @BeforeEach
    void capture() {
        warnings.start();
        warningLog.addAppender(warnings);
    }

    @AfterEach
    void release() {
        warningLog.detachAppender(warnings);
    }

    @Test
    void eachTable_isReportedAtMostOncePerMinute() {
        warning.withheld("public.notes", List.of("owner_id"));
        now.set(now.get().plusSeconds(59));
        warning.withheld("public.notes", List.of("owner_id"));
        warning.withheld("public.orders", List.of("secret"));
        now.set(now.get().plus(Duration.ofSeconds(1)));
        warning.withheld("public.notes", List.of("title"));

        assertThat(warnings.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "realtime_image_incomplete table=public.notes missing=[owner_id]",
                "realtime_image_incomplete table=public.orders missing=[secret]",
                "realtime_image_incomplete table=public.notes missing=[title]");
    }
}
