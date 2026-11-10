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
        if (maxKeepAliveRequests < 1 || keepAliveTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "app.overload.max-keep-alive-requests and keep-alive-timeout-ms must be at least 1");
        }
        this.maxConnections = multiply(cpus, connectionsPerCpu, "app.overload.max-connections-per-cpu");
        this.acceptCount = multiply(cpus, backlogPerCpu, "app.overload.accept-count-per-cpu");
        this.maxKeepAliveRequests = maxKeepAliveRequests;
        this.keepAliveTimeoutMillis = keepAliveTimeoutMillis;
    }

    private static int multiply(int cpus, int perCpu, String setting) {
        try {
            return Math.multiplyExact(cpus, perCpu);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    setting + "=" + perCpu + " times " + cpus + " CPUs does not fit in an int", overflow);
        }
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int acceptCount() {
        return acceptCount;
    }

    @Override
    public String toString() {
        return "maxConnections=" + maxConnections + ", acceptCount=" + acceptCount
                + ", maxKeepAliveRequests=" + maxKeepAliveRequests + ", keepAliveTimeoutMs=" + keepAliveTimeoutMillis;
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
