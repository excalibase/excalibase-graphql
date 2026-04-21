package io.github.excalibase.nosql.schema;

import java.util.regex.Pattern;

public final class NoSqlIdentifiers {

    public static final String NOSQL_SCHEMA = "nosql";
    public static final Pattern IDENT_PATTERN = Pattern.compile("^[a-zA-Z_]\\w{0,62}$");

    private NoSqlIdentifiers() {}

    public static String safeIdent(String value, String kind) {
        if (value == null || !IDENT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + kind + ": must match [a-zA-Z_]\\w{0,62}");
        }
        return value;
    }

    public static String qualifiedTable(String collection) {
        return NOSQL_SCHEMA + ".\"" + safeIdent(collection, "collection name") + "\"";
    }
}
