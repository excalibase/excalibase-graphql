package io.github.excalibase.config;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;

/**
 * The actuator is exempt from the project check only on the management port. If that port were also the public
 * one, the exemption would apply to public traffic, so the engine refuses to start with the two equal.
 */
@Context
public class ManagementPortGuard {

    private final int publicPort;
    private final int managementPort;

    public ManagementPortGuard(@Value("${micronaut.server.port:10000}") int publicPort,
                               @Value("${endpoints.all.port:-1}") int managementPort) {
        this.publicPort = publicPort;
        this.managementPort = managementPort;
    }

    @PostConstruct
    void check() {
        if (managementPort > 0 && managementPort == publicPort) {
            throw new IllegalStateException("The management port (MANAGEMENT_SERVER_PORT=" + managementPort
                    + ") equals the public port (SERVER_PORT=" + publicPort + "). The actuator would then be "
                    + "reachable on the public listener. Give the management port its own value.");
        }
    }
}
