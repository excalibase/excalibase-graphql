package io.github.excalibase.overload;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * First in a connection's pipeline. A connection over the bound is accepted by the kernel but nothing it
 * sends is read by the server until a slot frees, the same wait a client has in the accept queue.
 */
final class ConnectionAdmission extends ChannelInboundHandlerAdapter implements ConnectionGate.Waiter {

    private final ConnectionGate gate;
    private final KeepAliveRotation rotation;
    private final List<Object> held = new ArrayList<>();
    private ChannelHandlerContext context;
    private boolean admitted;
    private boolean finished;

    ConnectionAdmission(ConnectionGate gate, KeepAliveRotation rotation) {
        this.gate = gate;
        this.rotation = rotation;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
        admitted = gate.tryAdmit(this);
        updateListener(context.channel());
        if (admitted) {
            rotation.start();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (admitted) {
            context.fireChannelRead(message);
        } else {
            held.add(message);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext context) {
        if (admitted) {
            context.fireChannelReadComplete();
        }
    }

    /** Called by the gate, from any thread, when a slot is handed to this connection. */
    @Override
    public void admitted() {
        context.executor().execute(this::admitOnEventLoop);
    }

    private void admitOnEventLoop() {
        if (finished) {
            gate.release();
            return;
        }
        admitted = true;
        updateListener(context.channel());
        rotation.start();
        List<Object> replay = new ArrayList<>(held);
        held.clear();
        replay.forEach(context::fireChannelRead);
        if (!replay.isEmpty()) {
            context.fireChannelReadComplete();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        finished = true;
        held.forEach(ReferenceCountUtil::release);
        held.clear();
        if (admitted) {
            gate.release();
        } else {
            gate.abandon(this);
        }
        updateListener(context.channel());
        super.channelInactive(context);
    }

    /** A full gate stops the listener accepting; connections then queue in the kernel, as the bound promises. */
    private void updateListener(Channel connection) {
        Channel listener = connection.parent();
        if (listener != null) {
            // On the listener's own thread, reading the gate then, so a stale answer never overwrites a newer one.
            listener.eventLoop().execute(() -> listener.config().setAutoRead(!gate.isFull()));
        }
    }
}
