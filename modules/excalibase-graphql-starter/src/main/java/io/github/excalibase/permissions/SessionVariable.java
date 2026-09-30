package io.github.excalibase.permissions;

import java.util.Locale;
import java.util.regex.Pattern;

/** A session variable reference ({@code X-Excalibase-*}), held lower-cased as spec §2 compares names. */
public record SessionVariable(String name) implements Operand, PresetValue {

    static final String PREFIX = "x-excalibase-";

    private static final Pattern NAME = Pattern.compile("^x-excalibase-[a-z0-9_-]+$", Pattern.CASE_INSENSITIVE);

    public SessionVariable {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("Invalid session variable name");
        }
        name = name.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Any string starting with the prefix is meant as a variable, valid or not. */
    static boolean looksLikeVariable(String text) {
        return text.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }
}
