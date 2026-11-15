package io.github.excalibase.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A body sent chunked announces no length, so nothing can judge it before a route answers (a form body on a
 * JSON route was a 415 however large). Such a request is held until its body ends, up to the limit, as the
 * servlet build read it. One that goes past is passed on as a request announcing a length over the limit, with no
 * body, which {@link RequestBodyLimitFilter} refuses with 413 like any other; what is left of its body is dropped.
 */
final class ChunkedBodyLimitHandler extends ChannelInboundHandlerAdapter {

    private enum State { PASS, HOLD, DROP }

    private final LongSupplier maxBytes;
    private State state = State.PASS;
    private HttpRequest head;
    private final List<HttpContent> held = new ArrayList<>();
    private long size;

    ChunkedBodyLimitHandler(LongSupplier maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (state == State.PASS && message instanceof HttpRequest request && isChunkedWithoutLength(request)) {
            head = request;
            size = 0;
            state = State.HOLD;
            return;
        }
        if (state == State.PASS || !(message instanceof HttpContent content)) {
            context.fireChannelRead(message);
            return;
        }
        if (state == State.DROP) {
            drop(content);
            return;
        }
        hold(context, content);
    }

    private static boolean isChunkedWithoutLength(HttpRequest request) {
        return HttpUtil.isTransferEncodingChunked(request) && !request.headers().contains(HttpHeaderNames.CONTENT_LENGTH);
    }

    private void hold(ChannelHandlerContext context, HttpContent content) {
        size += content.content().readableBytes();
        if (size > maxBytes.getAsLong()) {
            releaseHeld();
            HttpRequest announced = new DefaultHttpRequest(head.protocolVersion(), head.method(), head.uri(), head.headers().copy());
            announced.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
            announced.headers().set(HttpHeaderNames.CONTENT_LENGTH, maxBytes.getAsLong() + 1);
            head = null;
            context.fireChannelRead(announced);
            context.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
            state = State.DROP;
            drop(content);
            return;
        }
        held.add(content);
        if (content instanceof LastHttpContent) {
            HttpRequest request = head;
            head = null;
            state = State.PASS;
            List<HttpContent> contents = new ArrayList<>(held);
            held.clear();
            context.fireChannelRead(request);
            contents.forEach(context::fireChannelRead);
        }
    }

    private void drop(HttpContent content) {
        boolean last = content instanceof LastHttpContent;
        ReferenceCountUtil.release(content);
        if (last) {
            state = State.PASS;
        }
    }

    private void releaseHeld() {
        held.forEach(ReferenceCountUtil::release);
        held.clear();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        releaseHeld();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        releaseHeld();
        super.channelInactive(context);
    }
}
