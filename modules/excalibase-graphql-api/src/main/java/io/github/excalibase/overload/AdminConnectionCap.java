package io.github.excalibase.overload;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The admin port is outside the main connection bound, so a full public port never locks the owner out, but it has a
 * small bound of its own: a connection over it is closed at once rather than queued, so whoever reaches the port
 * cannot hold file descriptors. The first-request timeout still applies to every connection within the cap.
 */
final class AdminConnectionCap extends ChannelInboundHandlerAdapter {

    static final int MAX_CONNECTIONS = 32;

    private final AtomicInteger open;
    private final int cap;
    private final KeepAliveRotation rotation;
    private boolean counted;

    AdminConnectionCap(AtomicInteger open, int cap, KeepAliveRotation rotation) {
        this.open = open;
        this.cap = cap;
        this.rotation = rotation;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        if (open.incrementAndGet() > cap) {
            open.decrementAndGet();
            context.close();
            return;
        }
        counted = true;
        rotation.start();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        release();
        super.channelInactive(context);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        release();
    }

    private void release() {
        if (counted) {
            counted = false;
            open.decrementAndGet();
        }
    }
}
