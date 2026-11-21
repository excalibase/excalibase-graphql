package io.github.excalibase.spi;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names the engine image that serves a database, so a build that does not carry the database can say which one to use.
 * The slim images are published as {@code <tag>-<database>}; the all-in-one image is the plain {@code <tag>}.
 * A new database plugin that ships its own slim image adds its name here.
 */
final class EngineImages {

    static final String REPOSITORY = "excalibase/excalibase-graphql";

    /** Every name a database goes by, to the one its image is tagged with. */
    private static final String POSTGRES = "postgres";
    private static final Map<String, String> IMAGE_SUFFIX = Map.of(
            POSTGRES, POSTGRES,
            "postgresql", POSTGRES,
            "mysql", "mysql",
            "sqlite", "sqlite");

    private EngineImages() {}

    /** The message for a database type this build does not serve; {@code served} are the types it does. */
    static String refusal(String requested, List<String> served) {
        if (served.isEmpty()) {
            return "Unsupported database type: " + requested + ". This engine build has no database plugin on its classpath.";
        }
        String suffix = IMAGE_SUFFIX.get(requested.toLowerCase(Locale.ROOT));
        if (suffix == null) {
            return "Unsupported database type: " + requested + ". This engine build serves: " + String.join(", ", served) + ".";
        }
        return "This engine build serves only " + String.join(", ", served) + ", not " + suffix + ". Use the " + suffix
                + " image (" + REPOSITORY + ":<tag>-" + suffix + ") or the all-in-one image " + REPOSITORY + ":<tag>.";
    }
}
