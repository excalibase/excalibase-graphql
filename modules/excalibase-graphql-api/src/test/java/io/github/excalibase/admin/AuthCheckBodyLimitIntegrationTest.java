package io.github.excalibase.admin;

import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** The 8 KB cap on a pasted token holds before the body is read, not after the server has buffered it. */
class AuthCheckBodyLimitIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final int ANSWER_WAIT_MILLIS = 5000;

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
    }

    /** Sends the headers of a POST that declares {@code declared} bytes and sends none of them; returns the status line. */
    private static String statusForDeclared(long declared, String secret) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", ADMIN_PORT)) {
            socket.setSoTimeout(ANSWER_WAIT_MILLIS);
            String head = "POST /_admin/v1/auth/check HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\n"
                    + HEADER + ": " + secret + "\r\nContent-Length: " + declared + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[64];
            int read = socket.getInputStream().read(buffer);
            return new String(buffer, 0, Math.max(read, 0), StandardCharsets.US_ASCII).lines().findFirst().orElse("");
        }
    }

    @Test
    @DisplayName("a declared body over the cap is 413 at once, with no body sent")
    void declaredTooLargeIsRefusedBeforeAnyRead() throws IOException {
        assertThat(statusForDeclared(TokenCheck.MAX_BYTES + 1L, SECRET)).contains("413");
        assertThat(statusForDeclared(8L * 1024 * 1024, SECRET)).contains("413");
    }

    @Test
    @DisplayName("the cap does not open the route to a caller without the admin secret")
    void wrongSecretIsStill401() throws IOException {
        assertThat(statusForDeclared(TokenCheck.MAX_BYTES + 1L, "wrong")).contains("401");
    }
}
