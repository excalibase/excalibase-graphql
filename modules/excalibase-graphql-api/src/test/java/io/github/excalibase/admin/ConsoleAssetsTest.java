package io.github.excalibase.admin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The built console's allowlist comes from its manifest alone; a request never names a classpath path. */
class ConsoleAssetsTest {

    private static final String PREFIX = "/console-next";

    private static ConsoleAssets fixture() {
        return ConsoleAssets.bundle(PREFIX, ConsoleAssets.CLASSPATH);
    }

    private static Map<String, byte[]> files(String manifest, String... names) {
        Map<String, byte[]> files = new HashMap<>();
        files.put(PREFIX + "/.vite/manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        files.put(PREFIX + "/index.html", "<html><head></head><body></body></html>".getBytes(StandardCharsets.UTF_8));
        for (String name : names) {
            files.put(PREFIX + "/" + name, new byte[] {1});
        }
        return files;
    }

    private static ConsoleAssets.Source source(Map<String, byte[]> files) {
        return new ConsoleAssets.Source() {
            @Override
            public boolean exists(String resource) {
                return files.containsKey(resource);
            }

            @Override
            public byte[] read(String resource) {
                return files.get(resource);
            }
        };
    }

    @Test
    void theManifestListsEveryFileThatIsServed() {
        ConsoleAssets assets = fixture();

        assertThat(assets.names()).containsExactlyInAnyOrder("index.html", "assets/index-abc123.js",
                "assets/vendor-789.js", "assets/index-def456.css");
    }

    @Test
    void contentTypesFollowTheExtensionAndHashedFilesAreImmutable() {
        ConsoleAssets assets = fixture();

        assertThat(assets.find("assets/index-abc123.js")).get().satisfies(asset -> {
            assertThat(asset.contentType()).isEqualTo("text/javascript; charset=utf-8");
            assertThat(asset.immutable()).isTrue();
        });
        assertThat(assets.find("assets/index-def456.css").orElseThrow().contentType()).isEqualTo("text/css; charset=utf-8");
        assertThat(assets.find("index.html")).get().satisfies(asset -> {
            assertThat(asset.contentType()).isEqualTo("text/html; charset=utf-8");
            assertThat(asset.immutable()).isFalse();
        });
    }

    @Test
    void theManifestItselfIsNeverServed() {
        ConsoleAssets assets = fixture();

        assertThat(assets.find(".vite/manifest.json")).isEmpty();
        assertThat(assets.find("manifest.json")).isEmpty();
    }

    @Test
    void onlyAnExactListedNameIsFound() {
        ConsoleAssets assets = fixture();

        for (String name : List.of("", "assets", "assets/", "assets/index-abc123.js/", "/assets/index-abc123.js",
                "assets//index-abc123.js", "assets/../index.html", "../application.yml", "assets/%2e%2e/index.html",
                "assets/INDEX-abc123.js", "assets/index-abc123.js.gz", "assets/index-abc123.js ", "assets\\index-abc123.js",
                "assets/index-abc123.js\u0000.css")) {
            assertThat(assets.find(name)).as(name).isEmpty();
        }
        assertThat(assets.find(null)).isEmpty();
    }

    @Test
    void aGzipSiblingIsServedAsTheCompressedBodyOfTheSameAsset() {
        ConsoleAssets assets = fixture();

        assertThat(assets.find("assets/index-abc123.js").orElseThrow().gzip()).isPresent();
        assertThat(assets.find("assets/vendor-789.js").orElseThrow().gzip()).isEmpty();
        assertThat(assets.find("assets/index-abc123.js").orElseThrow().body()).isNotEmpty();
    }

    @Test
    void aMissingManifestRefusesTheStart() {
        assertThatThrownBy(() -> ConsoleAssets.bundle("/console-absent", ConsoleAssets.CLASSPATH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("console-absent");
    }

    @Test
    void aListedFileThatIsMissingRefusesTheStart() {
        Map<String, byte[]> files = files("{\"index.html\":{\"file\":\"assets/a-1.js\",\"isEntry\":true}}");

        assertThatThrownBy(() -> ConsoleAssets.bundle(PREFIX, source(files)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("assets/a-1.js");
    }

    @Test
    void aNameThatCouldEscapeOrHideRefusesTheStart() {
        for (String bad : List.of("../secret.js", "assets/../../x.js", "/etc/passwd", ".vite/manifest.json",
                "assets//a.js", "assets\\\\a.js", "a b.js", "assets/%2e%2e/a.js", "")) {
            Map<String, byte[]> files = files("{\"index.html\":{\"file\":\"" + bad + "\",\"isEntry\":true}}");
            files.put(PREFIX + "/" + bad, new byte[] {1});

            assertThatThrownBy(() -> ConsoleAssets.bundle(PREFIX, source(files))).as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void anUnknownFileTypeRefusesTheStart() {
        Map<String, byte[]> files = files("{\"index.html\":{\"file\":\"assets/a-1.exe\",\"isEntry\":true}}", "assets/a-1.exe");

        assertThatThrownBy(() -> ConsoleAssets.bundle(PREFIX, source(files)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a-1.exe");
    }

    @Test
    void anIndexWithoutAHeadCannotTakeTheNonceAndRefusesTheStart() {
        Map<String, byte[]> files = files("{\"index.html\":{\"file\":\"assets/a-1.js\",\"isEntry\":true}}", "assets/a-1.js");
        files.put(PREFIX + "/index.html", "<html></html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> ConsoleAssets.bundle(PREFIX, source(files)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("</head>");
    }

    @Test
    void theLegacyConsoleKeepsItsTwelveFiles() {
        ConsoleAssets legacy = ConsoleAssets.legacy();

        assertThat(legacy.names()).containsExactlyInAnyOrder("index.html", "app.js", "api.js", "dom.js", "views.js",
                "editor.js", "grid.js", "jwt.js", "docmodel.js", "diff.js", "history.js", "style.css");
        assertThat(legacy.find("app.js").orElseThrow().immutable()).isFalse();
        assertThat(legacy.find("app.js").orElseThrow().contentType()).isEqualTo("text/javascript; charset=utf-8");
    }
}
