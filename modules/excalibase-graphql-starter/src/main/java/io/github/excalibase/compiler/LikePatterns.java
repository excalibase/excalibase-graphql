package io.github.excalibase.compiler;

/**
 * Text matched as itself inside a LIKE pattern: {@code %} and {@code _} in a value searched for by
 * contains, startsWith or endsWith are characters, not wildcards. The escape character is {@code !},
 * spelled the same in Postgres and MySQL whatever their backslash settings.
 */
public final class LikePatterns {

    /** Appended after the pattern of a LIKE built from {@link #literal}. */
    public static final String ESCAPE = " ESCAPE '!'";

    private LikePatterns() {
    }

    public static String literal(Object text) {
        return String.valueOf(text).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
