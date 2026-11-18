package io.github.excalibase.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The console's files, read from the classpath once. Only the names listed here are ever served, so a request can
 * never name a path: there is nothing to traverse. The same resources are registered for the native image.
 */
final class ConsoleAssets {

    /** One file: its bytes and the type it is served as. */
    record Asset(byte[] body, String contentType) {
    }

    private static final String HTML = "text/html; charset=utf-8";
    private static final String SCRIPT = "text/javascript; charset=utf-8";
    private static final String STYLE = "text/css; charset=utf-8";
    private static final Map<String, String> TYPES = Map.of(
            "index.html", HTML,
            "app.js", SCRIPT,
            "api.js", SCRIPT,
            "dom.js", SCRIPT,
            "views.js", SCRIPT,
            "style.css", STYLE);

    private final Map<String, Asset> assets;

    ConsoleAssets() {
        Map<String, Asset> loaded = new HashMap<>();
        for (Map.Entry<String, String> entry : TYPES.entrySet()) {
            loaded.put(entry.getKey(), new Asset(read(entry.getKey()), entry.getValue()));
        }
        this.assets = Map.copyOf(loaded);
    }

    Optional<Asset> find(String name) {
        return Optional.ofNullable(assets.get(name));
    }

    private static byte[] read(String name) {
        try (InputStream in = ConsoleAssets.class.getResourceAsStream("/console/" + name)) {
            if (in == null) {
                throw new IllegalStateException("the console file console/" + name + " is missing from the build");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
