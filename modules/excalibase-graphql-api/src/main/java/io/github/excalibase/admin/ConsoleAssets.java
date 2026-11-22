package io.github.excalibase.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The files of one console, found by exact name only. A request never builds a classpath path: it is looked up in an
 * allowlist that is fixed at start (the twelve legacy files, or what a build's manifest lists), and only then is the
 * file read, once, from a path the allowlist itself carries. There is nothing to traverse.
 */
public final class ConsoleAssets {

    /** Where the bytes come from; the classpath in the engine, a map in a test. */
    interface Source {
        boolean exists(String resource);

        byte[] read(String resource);
    }

    static final Source CLASSPATH = new Source() {
        @Override
        public boolean exists(String resource) {
            return ConsoleAssets.class.getResource(resource) != null;
        }

        @Override
        public byte[] read(String resource) {
            try (InputStream in = ConsoleAssets.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("the console file " + resource + " is missing from the build");
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    /** Which browser policy a file is served under: the legacy console's own, or the built one's (with a nonce on its page). */
    enum Policy { LEGACY, BUNDLE }

    /** One file: where it is, the type it is served as, and its compressed twin if the build made one. */
    public static final class Asset {
        private final Source source;
        private final String resource;
        private final String contentType;
        private final boolean immutable;
        private final Policy policy;
        private final boolean hasGzip;
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final AtomicReference<byte[]> gzip = new AtomicReference<>();

        private Asset(Source source, String resource, String contentType, boolean immutable, Policy policy,
                      boolean hasGzip) {
            this.source = source;
            this.resource = resource;
            this.contentType = contentType;
            this.immutable = immutable;
            this.policy = policy;
            this.hasGzip = hasGzip;
        }

        public String contentType() {
            return contentType;
        }

        /** A content-hashed file never changes under its name, so a browser may keep it for ever. */
        public boolean immutable() {
            return immutable;
        }

        Policy policy() {
            return policy;
        }

        public byte[] body() {
            return body.updateAndGet(held -> held != null ? held : source.read(resource));
        }

        public Optional<byte[]> gzip() {
            if (!hasGzip) {
                return Optional.empty();
            }
            return Optional.of(gzip.updateAndGet(held -> held != null ? held : source.read(resource + ".gz")));
        }
    }

    private static final String INDEX = "index.html";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String SCRIPT = "text/javascript; charset=utf-8";
    private static final String STYLE = "text/css; charset=utf-8";
    private static final Map<String, String> LEGACY_TYPES = Map.ofEntries(
            Map.entry(INDEX, HTML),
            Map.entry("app.js", SCRIPT),
            Map.entry("api.js", SCRIPT),
            Map.entry("dom.js", SCRIPT),
            Map.entry("views.js", SCRIPT),
            Map.entry("editor.js", SCRIPT),
            Map.entry("grid.js", SCRIPT),
            Map.entry("jwt.js", SCRIPT),
            Map.entry("docmodel.js", SCRIPT),
            Map.entry("diff.js", SCRIPT),
            Map.entry("history.js", SCRIPT),
            Map.entry("style.css", STYLE));
    private static final Map<String, String> BUNDLE_TYPES = Map.of(
            "html", HTML,
            "js", SCRIPT,
            "mjs", SCRIPT,
            "css", STYLE,
            "svg", "image/svg+xml",
            "png", "image/png",
            "woff2", "font/woff2");
    /** A segment is letters, digits, dots, dashes and underscores and does not start with a dot, so it is never {@code ..} or hidden. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String MANIFEST = "/.vite/manifest.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Asset> assets;

    private ConsoleAssets(Map<String, Asset> assets) {
        this.assets = Collections.unmodifiableMap(assets);
    }

    /** The plain-script console: its twelve files from {@code /console}. */
    public static ConsoleAssets legacy() {
        Map<String, Asset> found = new LinkedHashMap<>();
        LEGACY_TYPES.forEach((name, type) -> {
            String resource = "/console/" + name;
            if (!CLASSPATH.exists(resource)) {
                throw new IllegalStateException("the console file console/" + name + " is missing from the build");
            }
            found.put(name, new Asset(CLASSPATH, resource, type, false, Policy.LEGACY, false));
        });
        return new ConsoleAssets(found);
    }

    /**
     * The built console under {@code prefix}: the page and every file its manifest names, and nothing else (not the
     * manifest). Anything wrong refuses the start: a listed file that is not there, a name that could escape or hide,
     * a type this does not serve, a page that cannot take the nonce.
     */
    static ConsoleAssets bundle(String prefix, Source source) {
        if (!source.exists(prefix + MANIFEST)) {
            throw new IllegalStateException("the built console is missing: " + prefix + MANIFEST
                    + " is not in the build (build with -Pconsole-ui, or leave EXCALIBASE_CONSOLE_UI at legacy)");
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(INDEX);
        listed(manifest(source, prefix), names);
        Map<String, Asset> found = new LinkedHashMap<>();
        for (String name : names) {
            found.put(name, bundleAsset(prefix, source, name));
        }
        requireHead(found.get(INDEX));
        return new ConsoleAssets(found);
    }

    private static JsonNode manifest(Source source, String prefix) {
        try {
            return JSON.readTree(source.read(prefix + MANIFEST));
        } catch (IOException e) {
            throw new IllegalStateException("the built console's manifest cannot be read", e);
        }
    }

    private static void listed(JsonNode manifest, Set<String> names) {
        manifest.forEach(entry -> {
            addName(names, entry.path("file"));
            entry.path("css").forEach(file -> addName(names, file));
            entry.path("assets").forEach(file -> addName(names, file));
        });
    }

    private static void addName(Set<String> names, JsonNode file) {
        if (file.isMissingNode()) {
            return;
        }
        names.add(file.asText());
    }

    private static Asset bundleAsset(String prefix, Source source, String name) {
        if (!safe(name)) {
            throw new IllegalStateException("the built console lists a name it will not serve: '" + name + "'");
        }
        String type = BUNDLE_TYPES.get(name.substring(name.lastIndexOf('.') + 1));
        if (type == null || !name.contains(".")) {
            throw new IllegalStateException("the built console lists a file of a type it does not serve: " + name);
        }
        String resource = prefix + "/" + name;
        if (!source.exists(resource)) {
            throw new IllegalStateException("the built console lists " + name + ", which is missing from the build");
        }
        boolean hashed = name.startsWith("assets/");
        return new Asset(source, resource, type, hashed, Policy.BUNDLE, source.exists(resource + ".gz"));
    }

    /** Names are segments joined by single slashes; each segment must be safe. */
    private static boolean safe(String name) {
        for (String segment : name.split("/", -1)) {
            if (!SAFE_SEGMENT.matcher(segment).matches()) {
                return false;
            }
        }
        return true;
    }

    private static void requireHead(Asset index) {
        if (!new String(index.body(), java.nio.charset.StandardCharsets.UTF_8).contains("</head>")) {
            throw new IllegalStateException("the built console's index.html has no </head> to carry the style nonce");
        }
    }

    /** The file named exactly so; {@code null}, a path, a different case or an encoded dot finds nothing. */
    Optional<Asset> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(assets.get(name));
    }

    Set<String> names() {
        return assets.keySet();
    }
}
