package io.github.excalibase.admin;

import com.sun.net.httpserver.HttpServer;
import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** A {@code url} source is always read-only: the console exports, and a save is refused whatever it carries. */
class AdminMetadataWriteUrlIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final HttpServer DOCUMENTS = serve();

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    private static HttpServer serve() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/permissions.yaml", exchange -> {
                byte[] body = DOCUMENT.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        DOCUMENTS.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(Path.of("unused"), MANAGEMENT_PORT, ADMIN_PORT);
        properties.remove("metadata.file.path");
        properties.put("metadata.source", "url");
        properties.put("metadata.url.location", "http://127.0.0.1:" + DOCUMENTS.getAddress().getPort()
                + "/permissions.yaml");
        properties.put("metadata.url.poll-interval-ms", "50");
        return properties;
    }

    @Test
    void aUrlSourceIsNotWritable_andASaveIsRefused() {
        HttpAnswer served = admin.get("/_admin/v1/metadata", HEADER, SECRET);
        assertThat(served.<Boolean>jsonPath("$.writable")).isFalse();

        HttpAnswer answer = admin.send("PUT", "/_admin/v1/metadata", DOCUMENT + "# edited\n", HEADER, SECRET,
                "If-Match", "\"" + served.<String>jsonPath("$.sourceVersion") + "\"", "Content-Type", "text/plain");

        assertThat(answer.status()).isEqualTo(405);
        assertThat(answer.<String>jsonPath("$.error")).isEqualTo("source_read_only");
    }

    @Test
    void aReadOnlySourceAnswers405_beforeItAsksForIfMatch() {
        HttpAnswer answer = admin.send("PUT", "/_admin/v1/metadata", DOCUMENT, HEADER, SECRET, "Content-Type",
                "text/plain");

        assertThat(answer.status()).isEqualTo(405);
    }

    @Test
    void aUrlSourceKeepsNoHistory_andSaysSo() {
        HttpAnswer list = admin.get("/_admin/v1/history", HEADER, SECRET);

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.<Boolean>jsonPath("$.available")).isFalse();
        assertThat(list.json().path("entries")).isEmpty();
        assertThat(admin.get("/_admin/v1/history/1", HEADER, SECRET).status()).isEqualTo(404);
        assertThat(admin.get("/_admin/v1/metadata", HEADER, SECRET).<Boolean>jsonPath("$.historyAvailable")).isFalse();
    }
}
