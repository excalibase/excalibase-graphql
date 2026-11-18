package io.github.excalibase.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the admin console is on, where it listens and the secret that opens it. Off is the default and needs
 * nothing else. On, anything wrong refuses the start: a console that came up half configured is worse than none.
 *
 * @param secret null while the console is off
 */
public record AdminSettings(boolean enabled, int port, AdminSecret secret) {

    private static final String SECRET_VARIABLE = "EXCALIBASE_ADMIN_SECRET";
    private static final String SECRET_FILE_VARIABLE = "EXCALIBASE_ADMIN_SECRET_FILE";
    private static final String PORT_VARIABLE = "EXCALIBASE_ADMIN_PORT";
    /** The condition beans of the console carry: the flag is true, in any case. */
    public static final String ON_PATTERN = "(?i)true";
    public static final AdminSettings OFF = new AdminSettings(false, 0, null);

    /**
     * What start-up reads.
     *
     * @param serverPort     the public port, {@code <= 0} when it is chosen at random
     * @param managementPort the management port, {@code <= 0} when there is none
     * @param hosted         a policy URL or a provisioning URL is set: this deployment serves the hosted platform
     */
    public record Raw(boolean enabled, int port, String secret, String secretFile, int serverPort,
                      int managementPort, boolean hosted) {
        @Override
        public String toString() {
            return "AdminSettings.Raw[enabled=" + enabled + ", port=" + port + "]";
        }
    }

    public static AdminSettings resolve(Raw raw) {
        if (!raw.enabled()) {
            return OFF;
        }
        if (raw.hosted()) {
            throw new IllegalStateException("EXCALIBASE_CONSOLE_ENABLED=true is refused in hosted mode "
                    + "(app.security.rls.policy-url or app.security.multi-tenant.provisioning-url is set): the console "
                    + "administers one standalone engine, never the hosted platform");
        }
        requireUsablePort(raw);
        return new AdminSettings(true, raw.port(), secretOf(raw));
    }

    private static void requireUsablePort(Raw raw) {
        if (raw.port() < 1 || raw.port() > 65535) {
            throw new IllegalStateException(PORT_VARIABLE + " must be between 1 and 65535");
        }
        if (raw.port() == raw.serverPort()) {
            throw new IllegalStateException(PORT_VARIABLE + " must differ from the public port");
        }
        if (raw.port() == raw.managementPort()) {
            throw new IllegalStateException(PORT_VARIABLE + " must differ from the management port");
        }
    }

    private static AdminSecret secretOf(Raw raw) {
        boolean inline = raw.secret() != null && !raw.secret().isBlank();
        boolean file = raw.secretFile() != null && !raw.secretFile().isBlank();
        if (inline && file) {
            throw new IllegalStateException("set " + SECRET_VARIABLE + " or " + SECRET_FILE_VARIABLE + ", not both");
        }
        if (!inline && !file) {
            throw new IllegalStateException(SECRET_VARIABLE + " (or " + SECRET_FILE_VARIABLE + ") is required "
                    + "when EXCALIBASE_CONSOLE_ENABLED=true");
        }
        String name = inline ? SECRET_VARIABLE : SECRET_FILE_VARIABLE;
        try {
            return AdminSecret.of(inline ? raw.secret() : read(raw.secretFile()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " " + e.getMessage());
        }
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(SECRET_FILE_VARIABLE + " cannot be read: " + file.strip(), e);
        }
    }
}
