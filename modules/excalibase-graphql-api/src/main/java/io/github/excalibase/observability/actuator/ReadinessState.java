package io.github.excalibase.observability.actuator;

import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;

/** Whether the application takes traffic: it stops saying so when shutdown begins, so a load balancer drains it first. */
@Singleton
public class ReadinessState {

    private final AtomicBoolean refusing = new AtomicBoolean();

    public void refuseTraffic() {
        refusing.set(true);
    }

    public HealthStatus status() {
        return refusing.get() ? HealthStatus.OUT_OF_SERVICE : HealthStatus.UP;
    }
}
