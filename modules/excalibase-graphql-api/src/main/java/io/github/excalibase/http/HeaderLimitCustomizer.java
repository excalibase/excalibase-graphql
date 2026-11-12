package io.github.excalibase.http;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;

/**
 * A request line or headers over the codec's limit, or anything else the HTTP decoder cannot read, is decided
 * before routing: the container's HTML 400 page, and the connection is closed. Both ports.
 */
@Singleton
public class HeaderLimitCustomizer implements BeanCreatedEventListener<NettyServerCustomizer.Registry> {

    private static final String HANDLER_NAME = "excalibase-undecodable-request";

    @Override
    public NettyServerCustomizer.Registry onCreated(BeanCreatedEvent<NettyServerCustomizer.Registry> event) {
        NettyServerCustomizer.Registry registry = event.getBean();
        registry.register(new Customizer(null));
        return registry;
    }

    private record Customizer(Channel channel) implements NettyServerCustomizer {

        @Override
        public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
            return new Customizer(channel);
        }

        @Override
        public void onInitialPipelineBuilt() {
            if (channel != null && channel.pipeline().get(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC) != null) {
                channel.pipeline().addAfter(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC, HANDLER_NAME,
                        new RefuseUndecodable());
            }
        }
    }

    private static final class RefuseUndecodable extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof HttpRequest request && request.decoderResult().isFailure()) {
                ReferenceCountUtil.release(message);
                context.writeAndFlush(badRequest()).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            context.fireChannelRead(message);
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
}
