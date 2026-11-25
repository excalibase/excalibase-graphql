package io.github.excalibase.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bytes the client sent behind a WebSocket upgrade request wait for the switch instead of being read as HTTP. */
class PipelinedUpgradeHandlerTest {

    private static final String UPGRADE = "GET /p/graphql HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\n\r\n";
    private static final String PLAIN = "GET /p/graphql HTTP/1.1\r\nHost: h\r\n\r\n";
    private static final String FRAME = "\u0081\u0082abcdxy";

    private final EmbeddedChannel channel = new EmbeddedChannel(new PipelinedUpgradeHandler());

    private static ByteBuf bytes(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.ISO_8859_1);
    }

    private String read() {
        ByteBuf received = channel.readInbound();
        return received == null ? null : received.toString(StandardCharsets.ISO_8859_1);
    }

    private void answer(String status) {
        channel.writeOutbound(bytes("HTTP/1.1 " + status + "\r\ncontent-length: 0\r\n\r\n"));
        channel.runPendingTasks();
    }

    @Test
    @DisplayName("the request is read at once, the frame behind it only after the 101 is written")
    void frameWaitsForTheSwitch() {
        channel.writeInbound(bytes(UPGRADE + FRAME));

        assertThat(read()).isEqualTo(UPGRADE);
        assertThat(read()).isNull();

        answer("101 Switching Protocols");

        assertThat(read()).isEqualTo(FRAME);
    }

    @Test
    @DisplayName("frames that arrive while the switch is pending are kept in order")
    void laterFramesKeepOrder() {
        channel.writeInbound(bytes(UPGRADE + "one"));
        channel.writeInbound(bytes("two"));
        assertThat(read()).isEqualTo(UPGRADE);

        answer("101 Switching Protocols");

        assertThat(read()).isEqualTo("onetwo");
    }

    @Test
    @DisplayName("an upgrade request alone, and everything after the switch, passes straight through")
    void noRestMeansNoWaiting() {
        channel.writeInbound(bytes(UPGRADE));
        assertThat(read()).isEqualTo(UPGRADE);
        answer("101 Switching Protocols");

        channel.writeInbound(bytes(UPGRADE + FRAME));

        assertThat(read()).isEqualTo(UPGRADE + FRAME);
        assertThat(read()).isNull();
    }

    @Test
    @DisplayName("a plain request and what follows it is not held")
    void plainRequestPassesThrough() {
        channel.writeInbound(bytes(PLAIN + PLAIN));

        assertThat(read()).isEqualTo(PLAIN + PLAIN);
    }

    @Test
    @DisplayName("a refused upgrade drops the frame behind it and serves the next request")
    void refusedUpgradeDropsTheFrame() {
        channel.writeInbound(bytes(UPGRADE + FRAME));
        assertThat(read()).isEqualTo(UPGRADE);

        answer("403 Forbidden");

        assertThat(read()).isNull();
        channel.writeInbound(bytes(PLAIN));
        assertThat(read()).isEqualTo(PLAIN);
    }
}
