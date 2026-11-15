package io.github.excalibase.observability.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Micronaut's drain and our readiness listener both run first on shutdown, so which goes first is not
 * defined. Readiness therefore also takes part in the drain itself, which starts every part before it waits.
 */
class ReadinessDrainOrderTest {

    /** A drain part that never finishes, as a request still in flight. */
    private static final class HeldRequest implements GracefulShutdownCapable {
        private final CompletableFuture<Void> finished = new CompletableFuture<>();

        @Override
        public CompletionStage<?> shutdownGracefully() {
            return finished;
        }
    }

    private static void assertRefusesBeforeTheDrainWaits(boolean readinessFirst) {
        ReadinessState readiness = new ReadinessState();
        HeldRequest held = new HeldRequest();
        GracefulShutdownCapable asDrainPart = GracefulShutdownCapable.class.cast(readiness);

        CompletionStage<?> drain = GracefulShutdownCapable.shutdownAll(
                readinessFirst ? Stream.of(asDrainPart, held) : Stream.of(held, asDrainPart));

        assertThat(drain.toCompletableFuture()).as("the drain is waiting for the held request").isNotDone();
        assertThat(readiness.status()).isEqualTo(HealthStatus.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("readiness is OUT_OF_SERVICE once the drain is waiting, whichever part the drain started first")
    void refusesTrafficBeforeTheDrainWaits() {
        assertRefusesBeforeTheDrainWaits(true);
        assertRefusesBeforeTheDrainWaits(false);
    }
}
