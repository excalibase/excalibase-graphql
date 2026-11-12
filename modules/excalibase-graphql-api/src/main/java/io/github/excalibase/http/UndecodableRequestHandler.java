package io.github.excalibase.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

/**
 * A request line or headers over the codec's limit, or anything else the HTTP decoder cannot read, is decided
 * before routing: the container's HTML 400 page, and the connection is closed. Bytes that follow a WebSocket
 * upgrade request in the same read are not HTTP and fail to decode, but belong to the session about to start.
 */
final class UndecodableRequestHandler extends ChannelInboundHandlerAdapter {

    private boolean upgrading;

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof HttpRequest request)) {
            context.fireChannelRead(message);
            return;
        }
        if (request.decoderResult().isFailure() && !upgrading) {
            ReferenceCountUtil.release(message);
            context.writeAndFlush(badRequest()).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        upgrading = upgrading || isWebSocketUpgrade(request);
        context.fireChannelRead(message);
    }

    private static boolean isWebSocketUpgrade(HttpRequest request) {
        return HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE));
    }

    private static FullHttpResponse badRequest() {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
                Unpooled.copiedBuffer(TomcatErrorPage.BAD_REQUEST, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, TomcatErrorPage.CONTENT_TYPE)
                .setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return response;
    }
}
