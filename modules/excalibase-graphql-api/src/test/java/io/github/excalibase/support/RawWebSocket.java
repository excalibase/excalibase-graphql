package io.github.excalibase.support;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** A WebSocket client written byte by byte, for what a library client never sends: pipelined frames, empty close frames. */
public final class RawWebSocket implements AutoCloseable {

    public static final int TEXT = 0x1;
    public static final int CLOSE = 0x8;

    /** One frame the server sent. */
    public record Frame(int opcode, byte[] payload) {
        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        /** The reason text a close frame carries after its status code. */
        public String closeReason() {
            return payload.length < 2 ? "" : new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
        }

        /** The status code a close frame carries, or -1 for an empty one. */
        public int closeCode() {
            return payload.length < 2 ? -1 : ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final String handshakeAnswer;

    private RawWebSocket(Socket socket, InputStream in, String handshakeAnswer) {
        this.socket = socket;
        this.in = in;
        this.handshakeAnswer = handshakeAnswer;
    }

    /** Upgrades {@code path}; {@code firstText}, when not null, goes out in the same write as the upgrade request. */
    public static RawWebSocket connect(URI base, String path, String firstText) throws IOException {
        Socket socket = new Socket(base.getHost(), base.getPort());
        socket.setSoTimeout(8000);
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + base.getHost() + ":" + base.getPort()
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
                + Base64.getEncoder().encodeToString(key)
                + "\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n";
        ByteArrayOutputStream firstWrite = new ByteArrayOutputStream();
        firstWrite.write(request.getBytes(StandardCharsets.ISO_8859_1));
        if (firstText != null) {
            firstWrite.write(maskedFrame(TEXT, firstText.getBytes(StandardCharsets.UTF_8)));
        }
        socket.getOutputStream().write(firstWrite.toByteArray());
        socket.getOutputStream().flush();
        InputStream in = new BufferedInputStream(socket.getInputStream());
        return new RawWebSocket(socket, in, readHead(in));
    }

    public String handshakeAnswer() {
        return handshakeAnswer;
    }

    public void sendText(String text) throws IOException {
        socket.getOutputStream().write(maskedFrame(TEXT, text.getBytes(StandardCharsets.UTF_8)));
        socket.getOutputStream().flush();
    }

    /** A close frame with no status code, as a browser's {@code close()} with no arguments sends. */
    public void sendEmptyClose() throws IOException {
        socket.getOutputStream().write(maskedFrame(CLOSE, new byte[0]));
        socket.getOutputStream().flush();
    }

    /** The next frame; fails with a read timeout when the server sends none. */
    public Frame readFrame() throws IOException {
        int opcode = in.read() & 0x0F;
        int length = in.read() & 0x7F;
        if (length == 126) {
            length = (in.read() << 8) | in.read();
        }
        return new Frame(opcode, in.readNBytes(length));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            head.write(next);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] maskedFrame(int opcode, byte[] payload) {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        }
        frame.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        return frame.toByteArray();
    }
}
