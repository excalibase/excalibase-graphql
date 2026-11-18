package io.github.excalibase.admin;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

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
        } catch (SocketTimeoutException stillOpen) {
            return false;
        }
    }

    @Test
    @DisplayName("idle connections past the cap are closed; the ones within it stay until the first-request timeout")
    void capAndFirstRequestTimeout() throws IOException, InterruptedException {
        List<Socket> sockets = new ArrayList<>();
        try {
            for (int i = 0; i < CAP + 10; i++) {
                sockets.add(new Socket("localhost", ADMIN_PORT));
            }
            int closedAtOnce = 0;
            for (Socket socket : sockets) {
                closedAtOnce += closedByServer(socket) ? 1 : 0;
            }
            assertThat(closedAtOnce).isGreaterThanOrEqualTo(10);
            assertThat(sockets.size() - closedAtOnce).isLessThanOrEqualTo(CAP);

            Thread.sleep(2500);
            long stillOpen = 0;
            for (Socket socket : sockets) {
                stillOpen += closedByServer(socket) ? 0 : 1;
            }
            assertThat(stillOpen).as("idle connections after the first-request timeout").isZero();
        } finally {
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @Test
    @DisplayName("the admin API still answers once the flood has gone")
    void stillServes() {
        assertThat(AdminTestEngine.on(ADMIN_PORT).get("/_admin/v1/replicas", HEADER, SECRET).status()).isEqualTo(200);
    }
}
