package io.github.excalibase.observability.actuator;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import jakarta.inject.Singleton;

@Singleton
class ReadinessShutdownListener implements ApplicationEventListener<ServerShutdownEvent> {

    private final ReadinessState readiness;

    ReadinessShutdownListener(ReadinessState readiness) {
        this.readiness = readiness;
    }

    @Override
    public void onApplicationEvent(ServerShutdownEvent event) {
        readiness.refuseTraffic();
    }
}
