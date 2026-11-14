package io.github.excalibase.config.ws;

import java.time.Duration;

/**
 * What one engine allows its WebSockets. Socket caps are shares of the connection bound, which follows the CPUs,
 * so one image fits any size; subscription caps follow the CPUs the same way.
 */
record WsLimits(Duration initTimeout, int maxPerProject, int maxPerClient, int maxSubscriptionsPerSession,
                int maxSubscriptionsPerProject, int outboxCapacity, Duration slowConsumer) {

    static WsLimits of(int cpus, int connectionsPerCpu, int projectPercent, int clientPercent, int initTimeoutSeconds,
                       int subscriptionsPerSession, int subscriptionsPerProjectPerCpu, int outboxCapacity,
                       int slowConsumerSeconds) {
        long connections = (long) cpus * connectionsPerCpu;
        return new WsLimits(Duration.ofSeconds(Math.max(1, initTimeoutSeconds)),
                share(connections, projectPercent), share(connections, clientPercent),
                Math.max(1, subscriptionsPerSession),
                (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) cpus * subscriptionsPerProjectPerCpu)),
                Math.max(1, outboxCapacity), Duration.ofSeconds(Math.max(1, slowConsumerSeconds)));
    }

    private static int share(long connections, int percent) {
        long share = connections * Math.max(1, Math.min(100, percent)) / 100;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, share));
    }
}
