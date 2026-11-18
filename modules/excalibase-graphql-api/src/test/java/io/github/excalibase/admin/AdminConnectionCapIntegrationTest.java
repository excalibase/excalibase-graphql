package io.github.excalibase.admin;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Whoever reaches the admin port cannot hold more than a small number of connections, or hold one idle. */
class AdminConnectionCapIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final int CAP = 32;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(
                AdminTestEngine.documentFile(AdminTestEngine.DOCUMENT), -1, ADMIN_PORT);
        properties.put("server.tomcat.connection-timeout", "1500");
        return properties;
    }

    private static boolean closedByServer(Socket socket) throws IOException {
        socket.setSoTimeout(300);
        try {
            return socket.getInputStream().read() == -1;
        } catch (SocketTimeoutException _) {
            return false;
        }
    }

    /** Sockets opened by the test, all closed with it. */
    private static final class Opened implements AutoCloseable {
        private final List<Socket> sockets = new ArrayList<>();

        void open(int count) throws IOException {
            for (int i = 0; i < count; i++) {
                sockets.add(new Socket("localhost", ADMIN_PORT));
            }
        }

        long stillOpen() throws IOException {
            long open = 0;
            for (Socket socket : sockets) {
                open += closedByServer(socket) ? 0 : 1;
            }
            return open;
        }

        @Override
        public void close() throws IOException {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("idle connections past the cap are closed; the ones within it go at the first-request timeout")
    void capAndFirstRequestTimeout() throws IOException {
        try (Opened opened = new Opened()) {
            opened.open(CAP + 10);

            assertThat(opened.stillOpen()).isLessThanOrEqualTo(CAP);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(opened.stillOpen()).isZero());
        }
    }

    @Test
    @DisplayName("the admin API still answers once the flood has gone")
    void stillServes() {
        assertThat(AdminTestEngine.on(ADMIN_PORT).get("/_admin/v1/replicas", HEADER, SECRET).status()).isEqualTo(200);
    }
}
