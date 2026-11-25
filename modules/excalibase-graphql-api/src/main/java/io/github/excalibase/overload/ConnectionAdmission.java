package io.github.excalibase.overload;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * First in a connection's pipeline. A connection over the bound is accepted at once and nothing it sends is
 * read until a slot frees: reads are switched off while it waits, so what it sends stays in the kernel's
 * buffer instead of piling up here. The wait is short ({@link ConnectionBound#admissionWaitMillis()}); a
 * connection still without a slot then, or one that finds the queue full, is answered with a 503 and a
 * Retry-After instead of being left to time out. The answer is written before any request is read, so it
 * fits a plain HTTP request, a GraphQL POST and a WebSocket upgrade alike.
 */
final class ConnectionAdmission extends ChannelDuplexHandler implements ConnectionGate.Waiter {

    private final ConnectionGate gate;
    private final KeepAliveRotation rotation;
    private static final long DRAIN_MILLIS = 500;
    private static final String BODY = "{\"errors\":[{\"message\":\"The server is at its connection limit, "
            + "retry shortly\",\"extensions\":{\"code\":\"SERVICE_OVERLOADED\"}}]}";
    private static final int RETRY_AFTER_SPREAD_SECONDS = 3;

    /** Retry-After is spread over a few seconds so the clients refused together do not return together. */
    private static String refusal() {
        int retryAfter = 1 + ThreadLocalRandom.current().nextInt(RETRY_AFTER_SPREAD_SECONDS);
        return "HTTP/1.1 503 Service Unavailable\r\n"
                + "Retry-After: " + retryAfter + "\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                + "Content-Length: " + BODY.length() + "\r\nConnection: close\r\n\r\n" + BODY;
    }

    private final List<Object> held = new ArrayList<>();
    private final long waitMillis;
    private ChannelHandlerContext context;
    private ScheduledFuture<?> deadline;
    private boolean admitted;
    private boolean refused;
    private boolean refusing;
    private boolean finished;
    private boolean autoReadOnArrival;
    private boolean readWaiting;

    ConnectionAdmission(ConnectionGate gate, KeepAliveRotation rotation, long waitMillis) {
        this.gate = gate;
        this.rotation = rotation;
        this.waitMillis = waitMillis;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
        ConnectionGate.Outcome outcome = gate.tryAdmit(this);
        autoReadOnArrival = context.channel().config().isAutoRead();
        switch (outcome) {
            case ADMITTED -> {
                admitted = true;
                rotation.start();
            }
            case QUEUED -> {
                context.channel().config().setAutoRead(false);
                deadline = context.executor().schedule((Runnable) this::refuse, waitMillis, TimeUnit.MILLISECONDS);
            }
            case REFUSED -> refuseOnEventLoop();
        }
    }

    /** Answers 503 and closes; what the client already sent is drained for a moment so the answer is not lost to a reset. */
    private void refuse() {
        if (admitted || refused || finished) {
            return;
        }
        refused = true;
        gate.abandon(this);
        gate.recordRefusal();
        held.forEach(ReferenceCountUtil::release);
        held.clear();
        if (!gate.tryBeginRefusal()) {
            context.close();   // a flood of refusals is itself bounded: past the cap the connection is just dropped
            return;
        }
        refusing = true;
        context.channel().config().setAutoRead(true);
        context.writeAndFlush(Unpooled.copiedBuffer(refusal(), StandardCharsets.US_ASCII)).addListener(written -> {
            if (context.channel() instanceof DuplexChannel duplex) {
                duplex.shutdownOutput();
            }
            context.executor().schedule((Runnable) context::close, DRAIN_MILLIS, TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (admitted) {
            context.fireChannelRead(message);
        } else if (refused) {
            ReferenceCountUtil.release(message);
        } else {
            held.add(message);
        }
    }

    /** The server reads on demand; a read it asks for while this connection waits is made once it is admitted. */
    @Override
    public void read(ChannelHandlerContext context) {
        if (admitted || refused) {
            context.read();
        } else {
            readWaiting = true;
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
        try {
            context.executor().execute(this::admitOnEventLoop);
        } catch (RejectedExecutionException _) {
            gate.release();   // the connection's loop is gone: pass the slot on
        }
    }

    private void refuseOnEventLoop() {
        try {
            context.executor().execute(this::refuse);
        } catch (RejectedExecutionException _) {
            gate.recordRefusal();
            context.close();
        }
    }

    private void admitOnEventLoop() {
        if (finished || refused) {
            gate.release();
            return;
        }
        if (deadline != null) {
            deadline.cancel(false);
        }
        admitted = true;
        context.channel().config().setAutoRead(autoReadOnArrival);
        if (readWaiting) {
            readWaiting = false;
            context.read();
        }
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
        if (refusing) {
            refusing = false;
            gate.endRefusal();
        }
        if (deadline != null) {
            deadline.cancel(false);
        }
        held.forEach(ReferenceCountUtil::release);
        held.clear();
        if (admitted) {
            gate.release();
        } else {
            gate.abandon(this);
        }
        super.channelInactive(context);
    }
}
