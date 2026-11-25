package io.github.excalibase.overload;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sits behind the HTTP codec. A connection is closed after serving a fixed number of requests (the last
 * answer says {@code Connection: close}) so queued clients get a turn, and one that stays idle between
 * requests gives its slot back. A request body that stops arriving closes the connection after the same
 * wait as the first request (Tomcat's connection timeout), counted from the last piece received. A WebSocket, once upgraded, is neither rotated nor timed out.
 */
final class KeepAliveRotation extends ChannelDuplexHandler {

    private final int maxRequests;
    private final long idleMillis;
    private final long firstRequestMillis;
    private final AtomicInteger idleConnections;
    private ChannelHandlerContext context;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> bodyTimeout;
    private boolean idle;
    private boolean upgraded;
    private boolean discarding;
    private boolean finalResponse;
    private int requests;
    private int responses;

    KeepAliveRotation(ConnectionBound bound, long firstRequestMillis, AtomicInteger idleConnections) {
        this.maxRequests = bound.maxKeepAliveRequests();
        this.idleMillis = bound.keepAliveTimeoutMillis();
        this.firstRequestMillis = firstRequestMillis;
        this.idleConnections = idleConnections;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
        this.context = context;
    }

    /** Called once the connection holds a slot: the clock for the first request starts now. */
    void start() {
        if (context != null && context.executor().inEventLoop()) {
            waitForRequest(firstRequestMillis);
        } else if (context != null) {
            try {
                context.executor().execute(() -> waitForRequest(firstRequestMillis));
            } catch (RejectedExecutionException _) {
                // the loop is stopping and closes the connection with it
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (upgraded) {
            context.fireChannelRead(message);
            return;
        }
        if (message instanceof HttpRequest) {
            stopWaiting();
            requests++;
            discarding = requests > maxRequests;
        }
        trackBody(message);
        if (discarding) {
            ReferenceCountUtil.release(message);
            return;
        }
        context.fireChannelRead(message);
    }

    /** From the headers on, every piece of the body restarts the clock; the last piece stops it. */
    private void trackBody(Object message) {
        if (!(message instanceof HttpContent) && !(message instanceof HttpRequest)) {
            return;
        }
        stopBodyClock();
        if (message instanceof LastHttpContent) {
            return;
        }
        bodyTimeout = context.executor().schedule(
                () -> context.close().addListener(ChannelFutureListener.CLOSE_ON_FAILURE),
                firstRequestMillis, TimeUnit.MILLISECONDS);
    }

    private void stopBodyClock() {
        if (bodyTimeout != null) {
            bodyTimeout.cancel(false);
            bodyTimeout = null;
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (upgraded) {
            super.write(context, message, promise);
            return;
        }
        if (message instanceof HttpResponse interim && interim.status().codeClass() == HttpStatusClass.INFORMATIONAL
                && !HttpResponseStatus.SWITCHING_PROTOCOLS.equals(interim.status())) {
            super.write(context, message, promise);   // 100 Continue: the request is not answered yet
            return;
        }
        if (message instanceof HttpResponse response && HttpResponseStatus.SWITCHING_PROTOCOLS.equals(response.status())) {
            upgraded = true;
            stopWaiting();
            stopBodyClock();
        } else if (message instanceof HttpResponse response) {
            responses++;
            finalResponse = responses >= maxRequests;
            if (finalResponse) {
                HttpUtil.setKeepAlive(response, false);
            }
        }
        if (message instanceof LastHttpContent && !upgraded) {
            super.write(context, message, whenWritten(context, promise));
            return;
        }
        super.write(context, message, promise);
    }

    /** The server may write with a void promise, which cannot be listened to; a real one stands in for it. */
    private ChannelPromise whenWritten(ChannelHandlerContext context, ChannelPromise promise) {
        ChannelPromise tracked = promise.isVoid() ? context.newPromise() : promise;
        if (promise.isVoid()) {
            tracked.addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
        tracked.addListener(finished -> requestFinished(context));
        return tracked;
    }

    private void requestFinished(ChannelHandlerContext context) {
        if (finalResponse) {
            context.close();
        } else {
            waitForRequest(idleMillis);
        }
    }

    private void waitForRequest(long millis) {
        stopWaiting();
        if (context.channel().isActive()) {
            idle = true;
            idleConnections.incrementAndGet();
            timeout = context.executor().schedule(() -> context.close().addListener(ChannelFutureListener.CLOSE_ON_FAILURE),
                    millis, TimeUnit.MILLISECONDS);
        }
    }

    private void stopWaiting() {
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
        if (idle) {
            idle = false;
            idleConnections.decrementAndGet();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        stopWaiting();
        stopBodyClock();
        super.channelInactive(context);
    }
}
