package io.github.excalibase.overload;


/**
 * Bounds open connections to the CPUs available: a client over it waits briefly for a slot, then is
 * refused with a 503 instead of being parsed and parked on threads that starve the admitted ones.
 */
public final class ConnectionBound {

    private final int maxConnections;
    private final int acceptBacklog;
    private final int maxKeepAliveRequests;
    private final int keepAliveTimeoutMillis;
    private final int admissionWaitMillis;
    private final int maxWaiting;

    public static final int DEFAULT_ADMISSION_WAIT_MILLIS = 250;

    public ConnectionBound(int cpus, int connectionsPerCpu, int backlogPerCpu, int maxKeepAliveRequests,
                                 int keepAliveTimeoutMillis) {
        this(cpus, connectionsPerCpu, backlogPerCpu, maxKeepAliveRequests, keepAliveTimeoutMillis,
                DEFAULT_ADMISSION_WAIT_MILLIS);
    }

    public ConnectionBound(int cpus, int connectionsPerCpu, int backlogPerCpu, int maxKeepAliveRequests,
                                 int keepAliveTimeoutMillis, int admissionWaitMillis) {
        this(cpus, connectionsPerCpu, backlogPerCpu, maxKeepAliveRequests, keepAliveTimeoutMillis,
                admissionWaitMillis, connectionsPerCpu);
    }

    public ConnectionBound(int cpus, int connectionsPerCpu, int backlogPerCpu, int maxKeepAliveRequests,
                                 int keepAliveTimeoutMillis, int admissionWaitMillis, int waitQueuePerCpu) {
        if (cpus < 1 || connectionsPerCpu < 1 || backlogPerCpu < 1) {
            throw new IllegalArgumentException("cpus and per-cpu limits must be at least 1");
        }
        if (maxKeepAliveRequests < 1 || keepAliveTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "app.overload.max-keep-alive-requests and keep-alive-timeout-ms must be at least 1");
        }
        if (admissionWaitMillis < 0) {
            throw new IllegalArgumentException("app.overload.admission-wait-ms must not be negative");
        }
        if (waitQueuePerCpu < 0) {
            throw new IllegalArgumentException("app.overload.wait-queue-per-cpu must not be negative");
        }
        this.admissionWaitMillis = admissionWaitMillis;
        this.maxConnections = multiply(cpus, connectionsPerCpu, "app.overload.max-connections-per-cpu");
        this.acceptBacklog = multiply(cpus, backlogPerCpu, "app.overload.accept-count-per-cpu");
        this.maxWaiting = multiply(cpus, waitQueuePerCpu, "app.overload.wait-queue-per-cpu");
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

    /** How many connections over the bound may wait for a slot at once; the rest are refused at once. */
    public int maxWaiting() {
        return maxWaiting;
    }

    /** How long a connection over the bound may wait for a slot before it is refused with a 503. */
    public int admissionWaitMillis() {
        return admissionWaitMillis;
    }

    @Override
    public String toString() {
        return "maxConnections=" + maxConnections + ", acceptCount=" + acceptBacklog
                + ", maxKeepAliveRequests=" + maxKeepAliveRequests + ", keepAliveTimeoutMs=" + keepAliveTimeoutMillis
                + ", admissionWaitMs=" + admissionWaitMillis + ", maxWaiting=" + maxWaiting;
    }
}
