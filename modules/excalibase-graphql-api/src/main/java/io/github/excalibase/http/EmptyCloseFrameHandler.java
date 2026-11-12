package io.github.excalibase.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;

/** A close frame with no status code is a normal closure, which the server answers with code 1000, not an empty frame. */
final class EmptyCloseFrameHandler extends ChannelInboundHandlerAdapter {

    private static final int NO_STATUS = -1;

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof CloseWebSocketFrame close && close.statusCode() == NO_STATUS) {
            ReferenceCountUtil.release(message);
            context.fireChannelRead(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE.code(), ""));
            return;
        }
        context.fireChannelRead(message);
    }
}
