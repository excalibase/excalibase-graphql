package io.github.excalibase.sqlite;

import io.github.excalibase.schema.SchemaInfo;

import java.util.Locale;

/**
 * Reflects a SQLite column under the type the rest of the engine knows it by. SQLite keeps only what a column was
 * declared as, so the declared name decides, falling back to the affinity rules of the SQLite documentation:
 * a name with INT is an integer, with CHAR, CLOB or TEXT text, with BLOB bytes, with REAL, FLOA or DOUB a float.
 * Declared {@code BIGINT} and {@code NUMERIC} answer strings of every digit, as they do on Postgres and MySQL.
 */
final class SqliteColumnTypes {

    static final String BOOLEAN = "boolean";
    static final String BIGINT = "bigint";
    static final String NUMERIC = "numeric";
    static final String JSON = "json";
    static final String BYTES = "bytea";
    static final String TIMESTAMP = "timestamp";
    static final String TIMESTAMPTZ = "timestamptz";
    static final String DATE = "date";
    static final String UUID = "uuid";

    private SqliteColumnTypes() {
    }

    static void register(SchemaInfo info, String table, String column, String declared) {
        info.addColumn(table, column, engineType(declared));
        info.addColumnDeclaration(table, column, new SchemaInfo.ColumnDeclaration(declared, null, null));
    }

    static String engineType(String declared) {
        String name = declared == null ? "" : declared.toLowerCase(Locale.ROOT).replaceAll("\\(.*\\)", "")
                .replaceAll("\\s+", " ").trim();
        String named = byName(name);
        if (named != null) {
            return named;
        }
        if (name.contains("int")) {
            return "integer";
        }
        if (name.contains("char") || name.contains("clob") || name.contains("text") || name.isEmpty()) {
            return "text";
        }
        if (name.contains("blob")) {
            return BYTES;
        }
        if (name.contains("real") || name.contains("floa") || name.contains("doub")) {
            return "double precision";
        }
        return "text";
    }

    private static String byName(String name) {
        return switch (name) {
            case "boolean", "bool" -> BOOLEAN;
            case "bigint", "int8", "unsigned big int" -> BIGINT;
            case "smallint", "int2", "tinyint" -> "smallint";
            case "numeric", "decimal", "number" -> NUMERIC;
            case "json", "jsonb" -> JSON;
            case "uuid" -> UUID;
            case "date" -> DATE;
            case "datetime", "timestamp", "timestamp without time zone" -> TIMESTAMP;
            case "timestamptz", "timestamp with time zone" -> TIMESTAMPTZ;
            case "bytea" -> BYTES;
            default -> null;
        };
    }
}
