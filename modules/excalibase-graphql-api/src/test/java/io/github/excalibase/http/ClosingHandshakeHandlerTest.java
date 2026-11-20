package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A server that has sent its close frame keeps the connection until the client answers with its own (RFC 6455
 * 7.1.1). Closing the TCP connection at once lets a client that reads the last message and the close in one read
 * (Node's WebSocket inflates a compressed frame after the read) see the connection drop first and lose the message.
 */
class ClosingHandshakeHandlerTest {

    private static final Duration SHORT_GRACE = Duration.ofMillis(300);

    private final EmbeddedChannel channel = new EmbeddedChannel(new ClosingHandshakeHandler(SHORT_GRACE));

    private void sendClose() {
        channel.writeOutbound(new CloseWebSocketFrame(1008, "refused"));
    }

    @Test
    @DisplayName("a close after the close frame was sent waits for the client's close frame")
    void closeWaitsForTheClientsCloseFrame() {
        channel.writeOutbound(new TextWebSocketFrame("connection_error"));
        sendClose();

        channel.close();

        assertThat(channel.isOpen()).isTrue();

        channel.writeInbound(new CloseWebSocketFrame(1008, "refused"));
        channel.runPendingTasks();

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a client that never answers is cut off after the grace period")
    void silentClientIsCutOff() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try (AutoCloseable _ = () -> group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()) {
            CompletableFuture<Channel> accepted = new CompletableFuture<>();
            LocalAddress address = new LocalAddress("closing-handshake-" + System.nanoTime());
            new ServerBootstrap().group(group).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override
                        protected void initChannel(LocalChannel child) {
                            child.pipeline().addLast(new ClosingHandshakeHandler(SHORT_GRACE));
                            accepted.complete(child);
                        }
                    }).bind(address).sync();
            new Bootstrap().group(group).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter())
                    .connect(address).sync();
            Channel server = accepted.get(5, TimeUnit.SECONDS);
            server.writeAndFlush(new CloseWebSocketFrame(1008, "refused")).sync();

            server.close();

            assertThat(server.isOpen()).isTrue();
            server.closeFuture().await(5, TimeUnit.SECONDS);
            assertThat(server.isOpen()).isFalse();
        }
    }

    @Test
    @DisplayName("a second close request while one waits does not cut the wait short")
    void repeatedCloseKeepsWaiting() {
        sendClose();

        channel.close();
        channel.close();

        assertThat(channel.isOpen()).isTrue();

        channel.writeInbound(new CloseWebSocketFrame(1008, "refused"));
        channel.runPendingTasks();

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a close with no close frame sent, e.g. a plain HTTP connection, closes at once")
    void closeWithoutCloseFrameIsImmediate() {
        channel.close();

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    @DisplayName("a close after the client's close frame already arrived closes at once")
    void closeAfterClientCloseIsImmediate() {
        channel.writeInbound(new CloseWebSocketFrame(1000, ""));
        sendClose();

        channel.close();

        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    @DisplayName("the client's close frame still reaches the handlers behind this one")
    void clientCloseFramePassesThrough() {
        sendClose();

        channel.writeInbound(new CloseWebSocketFrame(1000, ""));

        assertThat((Object) channel.readInbound()).isInstanceOf(CloseWebSocketFrame.class);
    }
}
