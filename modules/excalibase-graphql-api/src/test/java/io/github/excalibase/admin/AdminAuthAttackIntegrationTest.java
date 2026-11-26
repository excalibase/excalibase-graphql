package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** What a caller does to dodge the Auth page's token-check limit, and what the page must never show back. */
class AdminAuthAttackIntegrationTest extends EngineIntegrationTest {

    private static final int ADMIN_PORT = FreePort.find();
    private static final Path FILE = AdminTestEngine.documentFile(DOCUMENT);
    private static final String CHECK = "/_admin/v1/auth/check";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int CHECK_LIMIT = 3;

    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.authPageOn(FILE, -1, ADMIN_PORT);
        properties.put("excalibase.admin.token-check-limit", Integer.toString(CHECK_LIMIT));
        properties.put("excalibase.admin.token-check-window-seconds", "600");
        properties.put("excalibase.admin.max-failures", "1000");
        return properties;
    }

    @Test
    @DisplayName("callers without the admin secret never reach the check limiter")
    void unauthorizedCallsDoNotSpendTheLimit() {
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(admin.post(CHECK, "x.y.z").status()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("forwarded-address headers do not give a caller a fresh limit")
    void forwardedHeadersDoNotResetTheLimit() {
        for (int attempt = 0; attempt < CHECK_LIMIT; attempt++) {
            assertThat(admin.post(CHECK, "x.y.z", HEADER, SECRET,
                    FORWARDED_FOR, "10.9.9." + attempt, "Forwarded", "for=10.8.8." + attempt).status()).isEqualTo(200);
        }

        assertThat(admin.post(CHECK, "x.y.z", HEADER, SECRET,
                FORWARDED_FOR, "203.0.113.77", "Forwarded", "for=203.0.113.77", "X-Real-IP", "203.0.113.77").status())
                .isEqualTo(429);
    }

    @Test
    @DisplayName("the settings answer never holds the admin secret or the token secret")
    void settingsHoldNoSecret() {
        assertThat(admin.get("/_admin/v1/auth", HEADER, SECRET).body())
                .doesNotContain(SECRET).doesNotContain(AuthTokens.SECRET);
    }
}
