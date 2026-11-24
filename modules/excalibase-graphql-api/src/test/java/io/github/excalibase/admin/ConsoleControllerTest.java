package io.github.excalibase.admin;

import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleControllerTest {

    private static final Pattern NONCE = Pattern.compile("'nonce-([A-Za-z0-9_-]+)'");
    private static final Pattern STYLE_TAG = Pattern.compile("<style nonce=\"([A-Za-z0-9_-]+)\"></style>");

    private static ConsoleController next() {
        return new ConsoleController(new ConsoleSite(ConsoleUi.NEXT, ConsoleAssets.legacy(),
                ConsoleAssets.bundle("/console-next", ConsoleAssets.CLASSPATH)));
    }

    private static ConsoleController legacy() {
        return new ConsoleController(new ConsoleSite(ConsoleUi.LEGACY, ConsoleAssets.legacy(), null));
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.getHeaders().get(name);
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void legacyAnswersWithTheUnchangedPolicyAndNoNonce() {
        HttpResponse<byte[]> page = legacy().file(null, null);

        assertThat(page.code()).isEqualTo(200);
        assertThat(header(page, "Content-Security-Policy")).isEqualTo(ConsoleController.LEGACY_POLICY)
                .contains("style-src 'self';").doesNotContain("nonce");
        assertThat(text(page)).doesNotContain("nonce");
    }

    @Test
    void theNewPageCarriesAFreshNonceInItsPolicyAndOnAStyleElement() {
        HttpResponse<byte[]> page = next().file(null, null);

        String policy = header(page, "Content-Security-Policy");
        Matcher inPolicy = NONCE.matcher(policy);
        Matcher inPage = STYLE_TAG.matcher(text(page));
        assertThat(inPolicy.find()).isTrue();
        assertThat(inPage.find()).isTrue();
        assertThat(inPage.group(1)).isEqualTo(inPolicy.group(1));
        assertThat(policy).contains("style-src 'self' 'nonce-" + inPolicy.group(1) + "'", "script-src 'self'",
                "require-trusted-types-for 'script'", "default-src 'none'")
                .doesNotContain("unsafe-inline", "unsafe-eval");
        assertThat(text(page)).doesNotContain("<meta name=\"nonce\"");
    }

    @Test
    void theNewConsoleMayLoadItsTypefacesFromItsOwnOriginAndNothingElse() {
        ConsoleController controller = next();

        assertThat(header(controller.file(null, null), "Content-Security-Policy")).contains("font-src 'self'");
        assertThat(header(controller.file("assets/index-abc123.js", null), "Content-Security-Policy")).contains("font-src 'self'")
                .doesNotContain("font-src *", "font-src https");
        assertThat(header(legacy().file(null, null), "Content-Security-Policy")).doesNotContain("font-src");
    }

    @Test
    void theNonceIsAtLeast128BitsAndNeverRepeats() {
        ConsoleController controller = next();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Matcher inPolicy = NONCE.matcher(header(controller.file("index.html", null), "Content-Security-Policy"));
            assertThat(inPolicy.find()).isTrue();
            assertThat(Base64.getUrlDecoder().decode(inPolicy.group(1))).hasSizeGreaterThanOrEqualTo(16);
            seen.add(inPolicy.group(1));
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void theNonceComesFromASecureRandomSource() throws ReflectiveOperationException {
        Field source = ConsoleController.class.getDeclaredField("random");

        assertThat(source.getType()).isEqualTo(SecureRandom.class);
    }

    @Test
    void theNewPageIsNeverCached() {
        HttpResponse<byte[]> page = next().file(null, null);

        assertThat(header(page, "Cache-Control")).isNull();
        assertThat(header(page, "Content-Type")).isEqualTo("text/html; charset=utf-8");
    }

    @Test
    void aHashedAssetIsImmutableAndHasNoNonce() {
        HttpResponse<byte[]> asset = next().file("assets/index-def456.css", null);

        assertThat(asset.code()).isEqualTo(200);
        assertThat(header(asset, "Cache-Control")).isEqualTo("public, max-age=31536000, immutable");
        assertThat(header(asset, "Content-Security-Policy")).contains("style-src 'self';").doesNotContain("nonce");
        assertThat(header(asset, "Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(header(asset, "Vary")).isEqualTo("Accept-Encoding");
    }

    @Test
    void gzipIsServedOnlyToAClientThatAsksAndKeepsThePolicy() throws IOException {
        ConsoleController controller = next();

        HttpResponse<byte[]> plain = controller.file("assets/index-abc123.js", null);
        HttpResponse<byte[]> zipped = controller.file("assets/index-abc123.js", "br, gzip;q=0.8");
        HttpResponse<byte[]> refused = controller.file("assets/index-abc123.js", "gzip;q=0");

        assertThat(header(plain, "Content-Encoding")).isNull();
        assertThat(header(refused, "Content-Encoding")).isNull();
        assertThat(header(zipped, "Content-Encoding")).isEqualTo("gzip");
        assertThat(header(zipped, "Vary")).isEqualTo("Accept-Encoding");
        assertThat(header(zipped, "Content-Security-Policy")).isEqualTo(header(plain, "Content-Security-Policy"));
        assertThat(header(zipped, "Content-Type")).isEqualTo("text/javascript; charset=utf-8");
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.body()))) {
            assertThat(in.readAllBytes()).isEqualTo(plain.body());
        }
    }

    @Test
    void anAssetWithoutAGzipSiblingIsNeverEncoded() {
        HttpResponse<byte[]> asset = next().file("assets/vendor-789.js", "gzip");

        assertThat(header(asset, "Content-Encoding")).isNull();
    }

    @Test
    void theLegacyPageIsReachableBesideTheNewOneAndKeepsItsPolicy() {
        HttpResponse<byte[]> old = next().file("legacy", null);

        assertThat(old.code()).isEqualTo(200);
        assertThat(text(old)).contains("/console/app.js");
        assertThat(header(old, "Content-Security-Policy")).isEqualTo(ConsoleController.LEGACY_POLICY);
    }

    @Test
    void theLegacyFilesStayLegacyUnderTheNextFlag() {
        HttpResponse<byte[]> script = next().file("app.js", null);

        assertThat(script.code()).isEqualTo(200);
        assertThat(header(script, "Cache-Control")).isNull();
        assertThat(header(script, "Content-Security-Policy")).isEqualTo(ConsoleController.LEGACY_POLICY);
    }

    @Test
    void anUnlistedNameIsA404WithNoPolicyOrBody() {
        for (String name : new String[] {".vite/manifest.json", "assets/../index.html", "nothing", "legacy/"}) {
            assertThat(next().file(name, null).code()).as(name).isEqualTo(404);
        }
        assertThat(legacy().file("legacy", null).code()).isEqualTo(404);
    }
}
