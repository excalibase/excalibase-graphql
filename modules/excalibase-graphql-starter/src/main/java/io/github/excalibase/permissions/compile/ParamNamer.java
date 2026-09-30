package io.github.excalibase.permissions.compile;

import java.util.regex.Pattern;

/**
 * Hands out bind-parameter names ({@code <prefix>_p<n>}) and subquery aliases ({@code <prefix>_a<n>}).
 * Use one namer per request so every name in the request's SQL is unique; not thread-safe.
 */
public final class ParamNamer {

    private static final Pattern PREFIX_SHAPE = Pattern.compile("^[a-z][a-z0-9_]{0,30}$");

    private final String prefix;
    private int nextParam;
    private int nextAlias;

    public ParamNamer(String prefix) {
        if (prefix == null || !PREFIX_SHAPE.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Invalid parameter prefix");
        }
        this.prefix = prefix;
    }

    public String nextParam() {
        return prefix + "_p" + nextParam++;
    }

    public String nextAlias() {
        return prefix + "_a" + nextAlias++;
    }
}
