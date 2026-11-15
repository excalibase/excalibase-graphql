package io.github.excalibase.http;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.http.netty.channel.ChannelPipelineCustomizer;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.netty.NettyServerCustomizer;
import io.netty.channel.Channel;
import jakarta.inject.Singleton;

import java.util.function.LongSupplier;

/**
 * Puts the handlers that make the wire behaviour match the servlet container's on every connection of every
 * port: one ahead of the HTTP decoder, the others right behind the HTTP codec, where they also stay behind the
 * WebSocket codecs after an upgrade.
 */
@Singleton
public class ProtocolCompatibilityCustomizer implements BeanCreatedEventListener<NettyServerCustomizer.Registry> {

    private static final String UNDECODABLE = "excalibase-undecodable-request";
    private static final String PIPELINED_UPGRADE = "excalibase-pipelined-upgrade";
    private static final String EMPTY_CLOSE = "excalibase-empty-close-frame";
    private static final String CHUNKED_LIMIT = "excalibase-chunked-body-limit";

    private final BeanProvider<HttpServerConfiguration> server;

    public ProtocolCompatibilityCustomizer(BeanProvider<HttpServerConfiguration> server) {
        this.server = server;
    }

    @Override
    public NettyServerCustomizer.Registry onCreated(BeanCreatedEvent<NettyServerCustomizer.Registry> event) {
        NettyServerCustomizer.Registry registry = event.getBean();
        registry.register(new Customizer(null, () -> server.get().getMaxRequestSize()));
        return registry;
    }

    private record Customizer(Channel channel, LongSupplier maxRequestSize) implements NettyServerCustomizer {

        @Override
        public NettyServerCustomizer specializeForChannel(Channel channel, ChannelRole role) {
            return new Customizer(channel, maxRequestSize);
        }

        @Override
        public void onInitialPipelineBuilt() {
            if (channel == null || channel.pipeline().get(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC) == null) {
                return;
            }
            channel.pipeline().addFirst(PIPELINED_UPGRADE, new PipelinedUpgradeHandler());
            channel.pipeline().addAfter(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC, EMPTY_CLOSE,
                    new EmptyCloseFrameHandler());
            channel.pipeline().addAfter(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC, CHUNKED_LIMIT,
                    new ChunkedBodyLimitHandler(maxRequestSize));
            channel.pipeline().addAfter(ChannelPipelineCustomizer.HANDLER_HTTP_SERVER_CODEC, UNDECODABLE,
                    new UndecodableRequestHandler());
        }
    }
}
