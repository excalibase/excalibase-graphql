package io.github.excalibase.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * First in the pipeline, ahead of the HTTP decoder. A client may send its first WebSocket frame in the same write
 * as the upgrade request; the decoder would read those bytes as a second, malformed HTTP request and lose them.
 * They are held until the 101 answer has been written, which is when the WebSocket codec takes the connection over.
 */
final class PipelinedUpgradeHandler extends ChannelDuplexHandler {

    private static final byte[] HEAD_END = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern UPGRADE_HEADER = Pattern.compile("(?im)^upgrade:\\s*websocket\\s*$");
    private static final String HTTP_ANSWER = "HTTP/1.1 ";
    private static final String SWITCHING_PROTOCOLS = "101";

    private enum Phase { HTTP, WAITING_FOR_SWITCH, SWITCHED }

    private Phase phase = Phase.HTTP;
    private ByteBuf held;

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof ByteBuf bytes) || phase == Phase.SWITCHED) {
            context.fireChannelRead(message);
        } else if (phase == Phase.WAITING_FOR_SWITCH) {
            hold(bytes);
        } else {
            readHttp(context, bytes);
        }
    }

    private void readHttp(ChannelHandlerContext context, ByteBuf bytes) {
        int headLength = upgradeHeadLength(bytes);
        if (headLength < 0 || headLength == bytes.readableBytes()) {
            context.fireChannelRead(bytes);
            return;
        }
        phase = Phase.WAITING_FOR_SWITCH;
        ByteBuf head = bytes.readRetainedSlice(headLength);
        hold(bytes);
        context.fireChannelRead(head);
    }

    /** The length of an upgrade request's head at the start of the read, or -1 when the read starts with anything else. */
    private static int upgradeHeadLength(ByteBuf bytes) {
        int end = indexOfHeadEnd(bytes);
        if (end < 0 || !bytes.toString(bytes.readerIndex(), 4, StandardCharsets.US_ASCII).equals("GET ")) {
            return -1;
        }
        int length = end + HEAD_END.length - bytes.readerIndex();
        String head = bytes.toString(bytes.readerIndex(), length, StandardCharsets.ISO_8859_1);
        return UPGRADE_HEADER.matcher(head).find() ? length : -1;
    }

    private static int indexOfHeadEnd(ByteBuf bytes) {
        return bytes.readableBytes() < 4 ? -1 : ByteBufIndex.of(bytes, HEAD_END);
    }

    private void hold(ByteBuf bytes) {
        if (held == null) {
            held = bytes;
        } else {
            held = Unpooled.wrappedBuffer(held, bytes);
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (phase != Phase.SWITCHED && message instanceof ByteBuf bytes && isHttpAnswer(bytes)) {
            if (isSwitch(bytes)) {
                promise.addListener(done -> context.executor().execute(() -> release(context)));
            } else {
                discardHeld();
            }
        }
        context.write(message, promise);
    }

    private static boolean isHttpAnswer(ByteBuf bytes) {
        return bytes.readableBytes() >= HTTP_ANSWER.length() + 3
                && bytes.toString(bytes.readerIndex(), HTTP_ANSWER.length(), StandardCharsets.US_ASCII).equals(HTTP_ANSWER);
    }

    private static boolean isSwitch(ByteBuf bytes) {
        return bytes.toString(bytes.readerIndex() + HTTP_ANSWER.length(), 3, StandardCharsets.US_ASCII)
                .equals(SWITCHING_PROTOCOLS);
    }

    private void release(ChannelHandlerContext context) {
        phase = Phase.SWITCHED;
        ByteBuf frames = held;
        held = null;
        if (frames != null) {
            context.fireChannelRead(frames);
        }
    }

    private void discardHeld() {
        if (held != null) {
            held.release();
            held = null;
        }
        phase = Phase.HTTP;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext context) {
        discardHeld();
    }
}
