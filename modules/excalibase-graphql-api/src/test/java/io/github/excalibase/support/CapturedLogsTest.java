package io.github.excalibase.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedLogsTest {

    /** Enough for a reader and a writer to overlap many times; the capture copies on every write, so more only slows it. */
    private static final int LINES = 5_000;
    private static final long PATIENCE_SECONDS = 60;

    @Test
    @DisplayName("reading the captured events while another thread logs never fails")
    void readWhileLogging() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("captured-logs-test");
        logger.setLevel(Level.INFO);
        CapturedLogs captured = new CapturedLogs();
        captured.start();
        logger.addAppender(captured);
        CountDownLatch readerRunning = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            awaitUninterruptibly(readerRunning);
            for (int i = 0; i < LINES; i++) {
                logger.info("line {}", i);
            }
        });
        try {
            writer.start();
            long reads = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
            while (writer.isAlive() && System.nanoTime() < deadline) {
                assertThat(captured.events().stream().filter(event -> event.getLevel() == Level.INFO).count())
                        .isGreaterThanOrEqualTo(0);
                readerRunning.countDown();
                reads++;
            }
            assertThat(writer.isAlive()).as("the writer finished in time").isFalse();
            assertThat(reads).isPositive();
            assertThat(captured.events()).hasSize(LINES);
        } finally {
            logger.detachAppender(captured);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
