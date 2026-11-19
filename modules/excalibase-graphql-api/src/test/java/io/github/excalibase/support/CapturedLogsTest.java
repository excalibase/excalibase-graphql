package io.github.excalibase.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedLogsTest {

    @Test
    @DisplayName("reading the captured events while another thread logs never fails")
    void readWhileLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger("captured-logs-test");
        logger.setLevel(Level.INFO);
        CapturedLogs captured = new CapturedLogs();
        captured.start();
        logger.addAppender(captured);
        AtomicBoolean writing = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 200_000 && writing.get(); i++) {
                logger.info("line {}", i);
            }
        });
        try {
            writer.start();
            long reads = 0;
            while (writer.isAlive()) {
                assertThat(captured.events().stream().filter(event -> event.getLevel() == Level.INFO).count())
                        .isGreaterThanOrEqualTo(0);
                reads++;
            }
            assertThat(reads).isPositive();
            assertThat(captured.events()).hasSize(200_000);
        } finally {
            writing.set(false);
            logger.detachAppender(captured);
        }
    }
}
