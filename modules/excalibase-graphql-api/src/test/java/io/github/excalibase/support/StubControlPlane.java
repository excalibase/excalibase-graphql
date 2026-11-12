package io.github.excalibase.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** A control plane stand-in on a free local port: serves fixed JSON and remembers the last credential it saw. */
public final class StubControlPlane implements AutoCloseable {

    private final HttpServer server;
    private volatile String lastAuthorization;

    public StubControlPlane() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException failure) {
            throw new UncheckedIOException("control plane stub failed to start", failure);
        }
    }

    public StubControlPlane serveJson(String path, String body) {
        return serveJson(path, () -> body);
    }

    public StubControlPlane serveJson(String path, Supplier<String> body) {
        server.createContext(path, exchange -> {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return this;
    }

    public StubControlPlane start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url(String path) {
        return "http://localhost:" + port() + path;
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
