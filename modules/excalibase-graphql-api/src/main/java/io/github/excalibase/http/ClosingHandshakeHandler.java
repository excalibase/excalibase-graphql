package io.github.excalibase.http;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.concurrent.ScheduledFuture;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Keeps the connection open after the server's close frame until the client answers with its own, or a grace
 * period passes (RFC 6455 7.1.1), unless the connection bound is reached. The framework closes the connection the moment its close frame is written; a
 * client that reads a last message and the close frame in one read, as Node's WebSocket does with a compressed
 * message it inflates after the read, then sees the connection drop first and never delivers the message: a refused
 * {@code connection_init} reached it as a bare 1006 with no reason.
 */
final class ClosingHandshakeHandler extends ChannelDuplexHandler {

    private final Duration grace;
    private final BooleanSupplier connectionsFull;
    private boolean closeFrameSent;
    private boolean peerClosed;
    private ChannelPromise deferredClose;
    private ScheduledFuture<?> cutOff;

    /**
     * @param grace           how long to wait for the client's close frame
     * @param connectionsFull true while the connection bound is reached: a waiting connection holds a slot a new
     *                        client needs, so then nothing waits
     */
    ClosingHandshakeHandler(Duration grace, BooleanSupplier connectionsFull) {
        this.grace = grace;
        this.connectionsFull = connectionsFull;
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (message instanceof CloseWebSocketFrame) {
            promise.addListener(written -> closeFrameSent = written.isSuccess());
        }
        context.write(message, promise);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof CloseWebSocketFrame) {
            peerClosed = true;
            closeNow(context);
        }
        context.fireChannelRead(message);
    }

    @Override
    public void close(ChannelHandlerContext context, ChannelPromise promise) {
        if (deferredClose != null) {
            // the framework asks again, e.g. from its own timer: the connection is already going to close
            context.channel().closeFuture().addListener(closed -> promise.trySuccess());
            return;
        }
        if (!closeFrameSent || peerClosed || !context.channel().isActive() || connectionsFull.getAsBoolean()) {
            context.close(promise);
            return;
        }
        deferredClose = promise;
        cutOff = context.executor().schedule(() -> closeNow(context), grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        closeNow(context);
        context.fireChannelInactive();
    }

    private void closeNow(ChannelHandlerContext context) {
        ChannelPromise promise = deferredClose;
        if (promise == null) {
            return;
        }
        deferredClose = null;
        cutOff.cancel(false);
        context.close(promise);
    }
}
