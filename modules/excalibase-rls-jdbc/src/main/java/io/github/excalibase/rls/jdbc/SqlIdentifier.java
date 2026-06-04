package io.github.excalibase.rls.jdbc;

import java.util.regex.Pattern;

/**
 * Validates column identifiers used in policy {@code Rule.field()} before they
 * are interpolated into SQL. JPA's criteria builder validated this implicitly;
 * raw JDBC must check explicitly.
 *
 * <p>Allowed: {@code [A-Za-z_][A-Za-z0-9_]*}. No dotted paths, no whitespace,
 * no quoting characters. Matches Postgres's standard unquoted identifier
 * grammar (and is a strict subset of MySQL's, so portable).
 */
public final class SqlIdentifier {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    private SqlIdentifier() {}

    public static String checkColumn(String name) {
        if (name == null || !SAFE.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Rejected unsafe SQL identifier: " + (name == null ? "<null>" : name));
        }
        return name;
    }

    /**
     * Quotes a validated identifier with SQL-standard double quotes for safe
     * inclusion in generated SQL. Callers must run {@link #checkColumn(String)}
     * first; this method does not re-validate.
     */
    public static String quote(String name) {
        return "\"" + name + "\"";
    }
}
