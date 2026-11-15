package io.github.excalibase.observability.actuator;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;

/** Readiness turns OUT_OF_SERVICE as shutdown begins, before the drain starts waiting for requests. */
@Singleton
class ReadinessShutdownListener implements ApplicationEventListener<ShutdownEvent>, Ordered {

    private final ReadinessState readiness;

    ReadinessShutdownListener(ReadinessState readiness) {
        this.readiness = readiness;
    }

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        readiness.refuseTraffic();
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}
