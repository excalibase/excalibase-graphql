package io.github.excalibase.admin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The console's files, read from the classpath once. Only the names listed here are ever served, so a request can
 * never name a path: there is nothing to traverse. The same resources are registered for the native image.
 */
public final class ConsoleAssets {

    /** One file: its text and the type it is served as. */
    record Asset(String body, String contentType) {
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

    private static final String BASE = "/console";

    private final Map<String, Asset> assets;

    /** Whether the path is the page itself or one of the files served under it: the only console paths there are. */
    public static boolean isServed(String path) {
        if (path == null) {
            return false;
        }
        return BASE.equals(path)
                || (path.startsWith(BASE + "/") && TYPES.containsKey(path.substring(BASE.length() + 1)));
    }

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

    private static String read(String name) {
        try (InputStream in = ConsoleAssets.class.getResourceAsStream("/console/" + name)) {
            if (in == null) {
                throw new IllegalStateException("the console file console/" + name + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
