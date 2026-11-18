package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/** The console is on: its page and assets come from the admin port only, behind the browser's own defences. */
class ConsoleServedIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"(/console/[^\"]+)\"");

    private final EngineClient management = AdminTestEngine.on(MANAGEMENT_PORT);
    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        return AdminTestEngine.consoleOn(AdminTestEngine.documentFile(DOCUMENT), MANAGEMENT_PORT, ADMIN_PORT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/console", "/console/index.html", "/console/app.js", "/console/style.css"})
    @DisplayName("the admin port serves the page and its assets without the secret: they hold none")
    void servedOnTheAdminPort(String path) {
        HttpAnswer answer = admin.get(path);

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(answer.body()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/console", "/console/app.js", "/console/style.css", "/console/nothing"})
    @DisplayName("the console is a 404 on the public port and on the management port, secret or not")
    void notFoundOffTheAdminPort(String path) {
        for (EngineClient other : List.of(http, management)) {
            assertThat(other.get(path).status()).isEqualTo(404);
            assertThat(other.get(path, HEADER, SECRET).status()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("every asset the page names resolves, with the right type")
    void assetsResolve() {
        String page = admin.get("/console").body();
        Matcher assets = ASSET.matcher(page);
        int found = 0;
        while (assets.find()) {
            found++;
            HttpAnswer asset = admin.get(assets.group(1));
            assertThat(asset.status()).as(assets.group(1)).isEqualTo(200);
        }
        assertThat(found).isGreaterThanOrEqualTo(2);
        assertThat(admin.get("/console/app.js").header("Content-Type")).contains("javascript");
        assertThat(admin.get("/console/style.css").header("Content-Type")).contains("text/css");
        assertThat(admin.get("/console").header("Content-Type")).contains("text/html");
    }

    @Test
    @DisplayName("the page and every asset carry the strict CSP and the other browser headers")
    void securityHeaders() {
        for (String path : List.of("/console", "/console/app.js", "/console/style.css")) {
            HttpAnswer answer = admin.get(path);
            String csp = answer.header("Content-Security-Policy");

            assertThat(csp).as(path).contains("default-src 'none'", "script-src 'self'", "connect-src 'self'",
                    "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'");
            assertThat(csp).as(path).doesNotContain("unsafe-inline", "unsafe-eval", "http:", "https:", "*");
            assertThat(answer.header("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
            assertThat(answer.header("Referrer-Policy")).as(path).isEqualTo("no-referrer");
            assertThat(answer.header("Cache-Control")).as(path).contains("no-store");
        }
    }

    @Test
    @DisplayName("the page has no inline script and no third-party origin")
    void pageIsSelfContained() {
        String page = admin.get("/console").body();

        assertThat(page).doesNotContainPattern("(?i)<script(?![^>]*\\ssrc=)[^>]*>")
                .doesNotContainPattern("(?i)\\son[a-z]+\\s*=")
                .doesNotContain("http://", "https://", "style=\"");
    }

    @Test
    @DisplayName("only the listed files are served: no traversal and no guessing")
    void onlyListedFiles() {
        for (String path : List.of("/console/..%2fapplication.yml", "/console/../application.yml",
                "/console/%2e%2e/application.yml", "/console/application.yml", "/console/app.js/", "/console/api")) {
            assertThat(admin.raw("GET", path).status()).as(path).isIn(400, 404);
        }
    }

    @Test
    @DisplayName("the console holds no secret and the admin API still wants one")
    void apiStillGuarded() {
        assertThat(admin.get("/console/app.js").body()).doesNotContain(SECRET);
        assertThat(admin.get("/_admin/v1/metadata").status()).isEqualTo(401);
        assertThat(admin.get("/_admin/v1/metadata", HEADER, SECRET).status()).isEqualTo(200);
    }
}
