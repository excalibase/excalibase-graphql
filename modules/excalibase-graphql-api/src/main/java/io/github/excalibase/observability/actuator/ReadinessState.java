package io.github.excalibase.observability.actuator;

import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the application takes traffic: it stops saying so when shutdown begins, so a load balancer drains it first.
 * It is also a part of Micronaut's graceful drain, which starts every part before it waits on any: readiness is
 * refused by the time the drain waits, however the shutdown listeners happen to be ordered.
 */
@Singleton
public class ReadinessState implements GracefulShutdownCapable {

    private final AtomicBoolean refusing = new AtomicBoolean();

    public void refuseTraffic() {
        refusing.set(true);
    }

    public HealthStatus status() {
        return refusing.get() ? HealthStatus.OUT_OF_SERVICE : HealthStatus.UP;
    }

    @Override
    public CompletionStage<?> shutdownGracefully() {
        refuseTraffic();
        return CompletableFuture.completedFuture(null);
    }
}
