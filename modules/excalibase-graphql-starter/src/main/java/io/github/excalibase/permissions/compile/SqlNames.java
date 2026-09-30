package io.github.excalibase.permissions.compile;

import java.util.regex.Pattern;

/** Identifier quoting for the Postgres dialect. */
final class SqlNames {

    /** A plain identifier, or one the engine's dialect already quoted ({@code "a1b2c3d4"}). */
    private static final Pattern ALIAS = Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_]{0,62}|\"[A-Za-z0-9_]{1,63}\")$");

    private SqlNames() {
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** {@code schema.name} as {@code "schema"."name"}; a bare name is quoted alone. */
    static String qualified(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? quote(name) : quote(name.substring(0, dot)) + "." + quote(name.substring(dot + 1));
    }

    static String checkAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Unsafe table alias");
        }
        return alias;
    }
}
