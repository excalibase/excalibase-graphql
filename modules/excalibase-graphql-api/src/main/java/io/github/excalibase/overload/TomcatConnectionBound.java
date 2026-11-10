package io.github.excalibase.overload;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;

/**
 * Bounds open connections to the CPUs available: excess clients wait in the kernel accept
 * backlog instead of being parsed and parked on virtual threads that starve the admitted ones.
 */
public class TomcatConnectionBound {

    private final int maxConnections;
    private final int acceptCount;
    private final int maxKeepAliveRequests;
    private final int keepAliveTimeoutMillis;

    public TomcatConnectionBound(int cpus, int connectionsPerCpu, int backlogPerCpu, int maxKeepAliveRequests,
                                 int keepAliveTimeoutMillis) {
        if (cpus < 1 || connectionsPerCpu < 1 || backlogPerCpu < 1) {
            throw new IllegalArgumentException("cpus and per-cpu limits must be at least 1");
        }
        this.maxConnections = cpus * connectionsPerCpu;
        this.acceptCount = cpus * backlogPerCpu;
        this.maxKeepAliveRequests = maxKeepAliveRequests;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
    }

    public void apply(Connector connector) {
        if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
            protocol.setMaxConnections(maxConnections);
            protocol.setAcceptCount(acceptCount);
            protocol.setMaxKeepAliveRequests(maxKeepAliveRequests);
            protocol.setKeepAliveTimeout(keepAliveTimeoutMillis);
        }
    }
}
