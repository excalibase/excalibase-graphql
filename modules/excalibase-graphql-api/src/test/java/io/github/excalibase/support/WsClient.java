package io.github.excalibase.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** A real WebSocket client against the running engine; received text frames queue up for the test to read. */
public final class WsClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WAIT_SECONDS = 10;

    private final WebSocket socket;
    private final Listener listener;

    private WsClient(WebSocket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    /** Opens the socket; a refused upgrade fails with an {@link ExecutionException}, see {@link #refusedStatus}. */
    public static WsClient open(URI uri, String origin, List<String> subprotocols)
            throws InterruptedException, ExecutionException, TimeoutException {
        Listener listener = new Listener();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(WAIT_SECONDS));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        if (!subprotocols.isEmpty()) {
            builder.subprotocols(subprotocols.getFirst(), subprotocols.subList(1, subprotocols.size()).toArray(String[]::new));
        }
        WebSocket socket = builder.buildAsync(uri, listener).get(WAIT_SECONDS, TimeUnit.SECONDS);
        return new WsClient(socket, listener);
    }

    /** The HTTP status the server answered the upgrade with, or -1 when the failure was not a refused upgrade. */
    public static int refusedStatus(Throwable failure) {
        HttpResponse<?> refusal = refusal(failure);
        return refusal == null ? -1 : refusal.statusCode();
    }

    /** The answer to a refused upgrade, or null when the failure was not one. */
    public static HttpResponse<?> refusal(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebSocketHandshakeException refusal) {
                return refusal.getResponse();
            }
        }
        return null;
    }

    public void send(String text) {
        socket.sendText(text, true).join();
    }

    public void sendJson(Object message) throws Exception {
        send(MAPPER.writeValueAsString(message));
    }

    /** The next text frame, or null when none arrives within {@code timeout}. */
    public String poll(Duration timeout) throws InterruptedException {
        return listener.frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public JsonNode pollJson(Duration timeout) throws Exception {
        String frame = poll(timeout);
        return frame == null ? null : MAPPER.readTree(frame);
    }

    /** Reads frames until one has {@code "type": type}, or fails after the wait. */
    public JsonNode await(String type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            JsonNode message = pollJson(Duration.ofMillis(200));
            if (message != null && type.equals(message.path("type").asText())) {
                return message;
            }
        }
        throw new AssertionError("no " + type + " message within " + WAIT_SECONDS + "s");
    }

    public boolean isOpen() {
        return !socket.isInputClosed() && !socket.isOutputClosed();
    }

    /** The close code the server sent, or null while the socket is open. */
    public Integer closeCode() {
        return listener.closeCode.get();
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").exceptionally(failure -> null).join();
        }
        socket.abort();
    }

    private static final class Listener implements WebSocket.Listener {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final AtomicReference<Integer> closeCode = new AtomicReference<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.set(statusCode);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }
    }
}
