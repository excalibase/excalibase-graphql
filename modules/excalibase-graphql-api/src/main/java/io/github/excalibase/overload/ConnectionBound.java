package io.github.excalibase.overload;


/**
 * Bounds open connections to the CPUs available: excess clients wait in the kernel accept
 * backlog instead of being parsed and parked on threads that starve the admitted ones.
 */
public final class ConnectionBound {

    private final int maxConnections;
    private final int acceptBacklog;
    private final int maxKeepAliveRequests;
    private final int keepAliveTimeoutMillis;

    public ConnectionBound(int cpus, int connectionsPerCpu, int backlogPerCpu, int maxKeepAliveRequests,
                                 int keepAliveTimeoutMillis) {
        if (cpus < 1 || connectionsPerCpu < 1 || backlogPerCpu < 1) {
            throw new IllegalArgumentException("cpus and per-cpu limits must be at least 1");
        }
        if (maxKeepAliveRequests < 1 || keepAliveTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "app.overload.max-keep-alive-requests and keep-alive-timeout-ms must be at least 1");
        }
        this.maxConnections = multiply(cpus, connectionsPerCpu, "app.overload.max-connections-per-cpu");
        this.acceptBacklog = multiply(cpus, backlogPerCpu, "app.overload.accept-count-per-cpu");
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

    public int acceptBacklog() {
        return acceptBacklog;
    }

    public int maxKeepAliveRequests() {
        return maxKeepAliveRequests;
    }

    public int keepAliveTimeoutMillis() {
        return keepAliveTimeoutMillis;
    }

    @Override
    public String toString() {
        return "maxConnections=" + maxConnections + ", acceptCount=" + acceptBacklog
                + ", maxKeepAliveRequests=" + maxKeepAliveRequests + ", keepAliveTimeoutMs=" + keepAliveTimeoutMillis;
    }
}
