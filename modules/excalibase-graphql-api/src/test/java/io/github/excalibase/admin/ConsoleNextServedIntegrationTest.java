package io.github.excalibase.admin;

import io.github.excalibase.support.EngineClient;
import io.github.excalibase.support.EngineIntegrationTest;
import io.github.excalibase.support.FreePort;
import io.github.excalibase.support.HttpAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static io.github.excalibase.admin.AdminTestEngine.DOCUMENT;
import static io.github.excalibase.admin.AdminTestEngine.HEADER;
import static io.github.excalibase.admin.AdminTestEngine.SECRET;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXCALIBASE_CONSOLE_UI unset, so the built console is the default: it answers at /console under a nonce policy, the
 * legacy one stays at /console/legacy, and neither is reachable off the admin port. The bundle here is the small
 * fixture on the test classpath; the real one comes from console-ui.
 */
class ConsoleNextServedIntegrationTest extends EngineIntegrationTest {

    private static final int MANAGEMENT_PORT = FreePort.find();
    private static final int ADMIN_PORT = FreePort.find();
    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9_-]+)'");
    private static final Pattern STYLE_TAG = Pattern.compile("<style nonce=\"([A-Za-z0-9_-]+)\"></style>");
    private static final String SCRIPT = "/console/assets/index-abc123.js";

    private final EngineClient management = AdminTestEngine.on(MANAGEMENT_PORT);
    private final EngineClient admin = AdminTestEngine.on(ADMIN_PORT);

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = AdminTestEngine.consoleOn(AdminTestEngine.documentFile(DOCUMENT),
                MANAGEMENT_PORT, ADMIN_PORT);
        return properties;
    }

    @Test
    @DisplayName("/console is the built page, with the style nonce in the policy and on a style element")
    void thePageIsTheBuiltOneWithANonce() {
        HttpAnswer answer = admin.get("/console");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("/console/assets/index-abc123.js");
        Matcher inPolicy = NONCE.matcher(answer.header("Content-Security-Policy"));
        Matcher inPage = STYLE_TAG.matcher(answer.body());
        assertThat(inPolicy.find()).isTrue();
        assertThat(inPage.find()).isTrue();
        assertThat(inPage.group(1)).isEqualTo(inPolicy.group(1));
        assertThat(answer.header("Content-Security-Policy")).contains("script-src 'self'", "connect-src 'self'",
                "frame-ancestors 'none'", "require-trusted-types-for 'script'")
                .doesNotContain("unsafe-inline", "unsafe-eval", "http:", "https:", "*");
        assertThat(answer.header("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("every request to the page gets a nonce of its own")
    void theNonceIsNeverReused() {
        String first = admin.get("/console").header("Content-Security-Policy");
        String second = admin.get("/console").header("Content-Security-Policy");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a hashed asset is served immutable under the plain policy, with the browser headers")
    void assetsAreImmutable() {
        HttpAnswer script = admin.get(SCRIPT);

        assertThat(script.status()).isEqualTo(200);
        assertThat(script.header("Content-Type")).contains("javascript");
        assertThat(script.header("Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(script.header("Content-Security-Policy")).contains("style-src 'self';").doesNotContain("nonce");
        assertThat(script.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(script.header("Vary")).contains("Accept-Encoding");
        assertThat(admin.get("/console/assets/index-def456.css").header("Content-Type")).contains("text/css");
    }

    @Test
    @DisplayName("gzip is sent once, to a client that asks, and decodes to the plain file")
    void gzipIsServedOnce() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + ADMIN_PORT + SCRIPT);
        HttpResponse<byte[]> zipped = client.send(HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> plain = client.send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(zipped.headers().firstValue("Content-Encoding")).contains("gzip");
        assertThat(zipped.headers().allValues("Content-Encoding")).hasSize(1);
        assertThat(zipped.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("script-src 'self'");
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.body()))) {
            assertThat(in.readAllBytes()).isEqualTo(plain.body());
        }
    }

    @Test
    @DisplayName("the old console is still reachable at /console/legacy, with its own assets and policy")
    void legacyStaysReachable() {
        HttpAnswer page = admin.get("/console/legacy");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("/console/app.js");
        assertThat(page.header("Content-Security-Policy")).contains("style-src 'self';").doesNotContain("nonce");
        assertThat(admin.get("/console/app.js").status()).isEqualTo(200);
        assertThat(admin.get("/console/style.css").status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/console", "/console/legacy", SCRIPT, "/console/assets/index-def456.css", "/console/app.js"})
    @DisplayName("the built console is a 404 on the public port and on the management port, secret or not")
    void notFoundOffTheAdminPort(String path) {
        for (EngineClient other : List.of(http, management)) {
            assertThat(other.get(path).status()).isEqualTo(404);
            assertThat(other.get(path, HEADER, SECRET).status()).isEqualTo(404);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/console/.vite/manifest.json", "/console/manifest.json", "/console/assets",
            "/console/assets/", "/console/assets/../application.yml", "/console/assets/%2e%2e/application.yml",
            "/console/assets/..%2fapplication.yml", "/console/assets/index-abc123.js.gz", "/console/assets/index-abc123.js/",
            "/console/legacy/", "/console/application.yml", "/console//assets/index-abc123.js"})
    @DisplayName("only the files the manifest lists are served: no manifest, no listing, no traversal, no guessing")
    void onlyListedFiles(String path) {
        assertThat(admin.raw("GET", path).status()).as(path).isIn(400, 404);
    }

    @Test
    @DisplayName("the page has no inline script, no inline style attribute and no third-party origin")
    void pageIsSelfContained() {
        String page = admin.get("/console").body();

        assertThat(page).doesNotContainPattern("(?i)<script(?![^>]*\\ssrc=)[^>]*>")
                .doesNotContainPattern("(?i)\\son[a-z]+\\s*=")
                .doesNotContain("http://", "https://", "style=\"");
    }

    @Test
    @DisplayName("the admin API still wants the secret and the console holds none")
    void apiStillGuarded() {
        assertThat(admin.get(SCRIPT).body()).doesNotContain(SECRET);
        assertThat(admin.get("/_admin/v1/metadata").status()).isEqualTo(401);
        assertThat(admin.get("/_admin/v1/metadata", HEADER, SECRET).status()).isEqualTo(200);
    }
}
