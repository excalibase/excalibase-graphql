package io.github.excalibase.config;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import jakarta.inject.Singleton;

/**
 * Netty hands the request target to a strict URI parser that answers a bare 400 for {@code {}}, quotes,
 * brackets or a lone {@code %}. PostgREST filters carry those unencoded, so the target is escaped before parsing.
 */
@Singleton
public class RelaxedRequestTargetCustomizer implements BeanCreatedEventListener<NettyServerCustomizer.Registry> {

    private static final String HANDLER_NAME = "excalibase-relaxed-request-target";

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
                        new EncodeTarget());
            }
        }
    }

    private static final class EncodeTarget extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof HttpRequest request) {
                request.setUri(RequestTargetEncoding.encode(request.uri()));
            }
            context.fireChannelRead(message);
        }
    }
}
