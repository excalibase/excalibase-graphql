package io.github.excalibase.observability.actuator;

import io.github.excalibase.metadata.MetadataHealth;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.graceful.GracefulShutdownCapable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the application takes traffic: it stops saying so when shutdown begins, so a load balancer drains it first.
 * It is also a part of Micronaut's graceful drain, which starts every part before it waits on any: readiness is
 * refused by the time the drain waits, however the shutdown listeners happen to be ordered. A replica whose metadata
 * source has no good document yet also says no, since it refuses every read.
 */
@Singleton
public class ReadinessState implements GracefulShutdownCapable {

    private final AtomicBoolean refusing = new AtomicBoolean();
    private final MetadataHealth metadata;

    public ReadinessState() {
        this(null);
    }

    @Inject
    public ReadinessState(@Nullable MetadataHealth metadata) {
        this.metadata = metadata;
    }

    public void refuseTraffic() {
        refusing.set(true);
    }

    public HealthStatus status() {
        boolean noMetadata = metadata != null && !metadata.ready();
        return refusing.get() || noMetadata ? HealthStatus.OUT_OF_SERVICE : HealthStatus.UP;
    }

    @Override
    public CompletionStage<?> shutdownGracefully() {
        refuseTraffic();
        return CompletableFuture.completedFuture(null);
    }
}
