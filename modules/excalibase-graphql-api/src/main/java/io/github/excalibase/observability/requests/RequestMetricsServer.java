package io.github.excalibase.observability.requests;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * The internal port that serves the per-project scrape at {@code GET /metrics} and nothing else, on its own
 * socket so the public port never carries it. It binds the configured address alone, or every interface.
 */
@Context
@Requires(property = "app.request-metrics.enabled", value = "true")
public final class RequestMetricsServer implements AutoCloseable {

    private static final String SCRAPE_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String METRICS_PATH = "/metrics";

    private final HttpServer server;
    private final RequestMetrics metrics;

    public RequestMetricsServer(RequestMetrics metrics,
                                @Value("${app.request-metrics.port:9464}") int port,
                                @Value("${app.request-metrics.address:}") String address) {
        this.metrics = metrics;
        this.server = open(port, address.strip());
        server.createContext("/", this::answer);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static HttpServer open(int port, String address) {
        try {
            InetSocketAddress socket = address.isEmpty() ? new InetSocketAddress(port)
                    : new InetSocketAddress(InetAddress.getByName(address), port);
            return HttpServer.create(socket, 0);
        } catch (UnknownHostException unresolvable) {
            throw new IllegalStateException("app.request-metrics.address '" + address + "' does not resolve", unresolvable);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot open the request-metrics port " + port, failure);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    InetAddress boundAddress() {
        return server.getAddress().getAddress();
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) || !METRICS_PATH.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", SCRAPE_CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        server.stop(0);
    }
}
