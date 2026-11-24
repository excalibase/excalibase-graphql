package io.github.excalibase.admin;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * What {@code /console} serves: one answer to "is this path a console file" for both the controller and
 * {@code AdminPortFilter}, so the page cannot be reachable by one and refused by the other. With the new console on,
 * its files answer first and the legacy console stays at {@code /console/legacy} with its own files.
 */
@Context
@Requires(property = "excalibase.console.enabled", pattern = AdminSettings.ON_PATTERN)
public final class ConsoleSite {

    private static final String BASE = "/console";
    private static final String BUNDLE_ROOT = "/console-next";
    private static final String LEGACY_ALIAS = "legacy";
    private static final String INDEX = "index.html";
    private static final String MANIFEST = "/.vite/manifest.json";

    private final ConsoleUi ui;
    private final ConsoleAssets legacy;
    private final ConsoleAssets bundle;

    @Inject
    ConsoleSite(@Nullable @Property(name = "excalibase.console.ui") String ui) {
        this(ui == null ? defaultUi(ConsoleAssets.CLASSPATH) : ConsoleUi.parse(ui));
    }

    /**
     * What {@code /console} serves when nothing is set: the built console. A jar made without it (a developer build
     * with {@code -Dconsole.ui.skip=true}) has only the legacy console, so that is what it serves; naming
     * {@code next} on such a jar still refuses the start.
     */
    static ConsoleUi defaultUi(ConsoleAssets.Source source) {
        return source.exists(BUNDLE_ROOT + MANIFEST) ? ConsoleUi.NEXT : ConsoleUi.LEGACY;
    }

    private ConsoleSite(ConsoleUi ui) {
        this(ui, ConsoleAssets.legacy(), ui == ConsoleUi.NEXT ? ConsoleAssets.bundle(BUNDLE_ROOT, ConsoleAssets.CLASSPATH) : null);
    }

    ConsoleSite(ConsoleUi ui, ConsoleAssets legacy, ConsoleAssets bundle) {
        this.ui = ui;
        this.legacy = legacy;
        this.bundle = bundle;
    }

    /** A site that serves only the legacy console, as before the flag existed. */
    public static ConsoleSite legacyOnly() {
        return new ConsoleSite(ConsoleUi.LEGACY, ConsoleAssets.legacy(), null);
    }

    /** The file for a name under {@code /console}; the empty name is the page. */
    Optional<ConsoleAssets.Asset> find(String name) {
        String wanted = name == null || name.isEmpty() ? INDEX : name;
        if (ui == ConsoleUi.LEGACY) {
            return legacy.find(wanted);
        }
        if (LEGACY_ALIAS.equals(wanted)) {
            return legacy.find(INDEX);
        }
        Optional<ConsoleAssets.Asset> built = bundle.find(wanted);
        return built.isPresent() || INDEX.equals(wanted) ? built : legacy.find(wanted);
    }

    /** Whether the raw request path is the page or one of the files served under it: the only console paths there are. */
    public boolean isServed(String path) {
        if (path == null) {
            return false;
        }
        if (BASE.equals(path)) {
            return true;
        }
        if (!path.startsWith(BASE + "/")) {
            return false;
        }
        String name = path.substring(BASE.length() + 1);
        return !name.isEmpty() && (LEGACY_ALIAS.equals(name) ? ui == ConsoleUi.NEXT : find(name).isPresent());
    }
}
