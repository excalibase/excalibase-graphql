package io.github.excalibase.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A smoke check that reading while another thread logs does not fail, with the overlap guaranteed: the writer stops
 * half way until a read has seen the capture part full. It cannot tell a thread-safe list from a plain one under a
 * single writer, whose appends are already serialised by the appender.
 */
class CapturedLogsTest {

    /** Enough for a reader and a writer to overlap; the capture copies on every write, so more only slows it. */
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
        CountDownLatch readSomeOfIt = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            awaitUninterruptibly(readerRunning);
            for (int i = 0; i < LINES; i++) {
                if (i == LINES / 2) {
                    awaitUninterruptibly(readSomeOfIt);
                }
                logger.info("line {}", i);
            }
        });
        writer.setDaemon(true);
        try {
            writer.start();
            readerRunning.countDown();
            long overlapping = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
            while (writer.isAlive() && System.nanoTime() < deadline) {
                int seen = captured.events().size();
                if (seen > 0 && seen < LINES) {
                    overlapping++;
                    readSomeOfIt.countDown();
                }
            }
            assertThat(writer.isAlive()).as("the writer finished in time").isFalse();
            assertThat(overlapping).as("reads taken while the writer was part way").isPositive();
            assertThat(captured.events()).hasSize(LINES);
        } finally {
            readerRunning.countDown();
            readSomeOfIt.countDown();
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
