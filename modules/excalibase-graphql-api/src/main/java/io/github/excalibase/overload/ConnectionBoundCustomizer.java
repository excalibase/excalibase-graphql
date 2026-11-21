package io.github.excalibase.overload;

import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies the connection bound to every connection of the main port. Internal ports (the management port,
 * the request-metrics port) are left unbounded, so a full main port never fails a health probe. The admin port
 * has a small bound of its own ({@link AdminConnectionCap}).
 */
final class ConnectionBoundCustomizer implements NettyServerCustomizer {

    private static final String ADMISSION = "excalibase-connection-admission";
    private static final String ROTATION = "excalibase-keep-alive-rotation";

    private final ConnectionGate gate;
    private final ConnectionBound bound;
    private final long firstRequestMillis;
    private final Set<Integer> internalPorts;
    private final int adminPort;
    private final AtomicInteger adminOpen;
    private final Channel connection;

    ConnectionBoundCustomizer(ConnectionGate gate, ConnectionBound bound, long firstRequestMillis,
                              Set<Integer> internalPorts, int adminPort) {
        this(gate, bound, firstRequestMillis, internalPorts, adminPort, new AtomicInteger(), null);
    }

    private ConnectionBoundCustomizer(ConnectionGate gate, ConnectionBound bound, long firstRequestMillis,
                                      Set<Integer> internalPorts, int adminPort, AtomicInteger adminOpen,
                                      Channel connection) {
        this.gate = gate;
        this.bound = bound;
        this.firstRequestMillis = firstRequestMillis;
        this.internalPorts = internalPorts;
        this.adminPort = adminPort;
        this.adminOpen = adminOpen;
        this.connection = connection;
    }

    @Override
    public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
        if (role == ChannelRole.CONNECTION && channel.localAddress() instanceof InetSocketAddress local
                && !internalPorts.contains(local.getPort())) {
            return new ConnectionBoundCustomizer(gate, bound, firstRequestMillis, internalPorts, adminPort, adminOpen,
                    channel);
        }
        return this;
    }

    @Override
    public void onInitialPipelineBuilt() {
        if (connection == null) {
            return;
        }
        ChannelPipeline pipeline = connection.pipeline();
        boolean admin = ((InetSocketAddress) connection.localAddress()).getPort() == adminPort;
        KeepAliveRotation rotation = new KeepAliveRotation(bound, firstRequestMillis,
                admin ? new AtomicInteger() : gate.idleConnections());
        if (pipeline.get(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC) != null) {
            pipeline.addAfter(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC, ROTATION, rotation);
        } else {
            pipeline.addLast(ROTATION, rotation);
        }
        pipeline.addFirst(ADMISSION, admin
                ? new AdminConnectionCap(adminOpen, AdminConnectionCap.MAX_CONNECTIONS, rotation)
                : new ConnectionAdmission(gate, rotation, bound.admissionWaitMillis()));
    }
}
