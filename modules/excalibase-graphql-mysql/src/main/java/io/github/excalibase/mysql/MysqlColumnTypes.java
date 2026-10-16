package io.github.excalibase.mysql;

import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reflects a MySQL column under the type the rest of the engine knows it by: {@code tinyint(1)} is a
 * boolean, {@code datetime} a timestamp without zone, {@code timestamp} a timestamp with zone, and an
 * ENUM a GraphQL enum of its labels named {@code <table>_<column>_enum}.
 */
final class MysqlColumnTypes {

    static final String BOOLEAN = "boolean";
    static final String DATETIME = "timestamp";
    static final String TIMESTAMP = "timestamptz";

    private static final String ENUM_SUFFIX = "_enum";
    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");
    private static final List<String> RESERVED_ENUM_VALUES = List.of("true", "false", "null");

    private MysqlColumnTypes() {
    }

    static void register(SchemaInfo info, String table, String column, String dataType, String columnType) {
        String type = dataType.toLowerCase(Locale.ROOT);
        String declared = columnType == null ? type : columnType.toLowerCase(Locale.ROOT);
        switch (type) {
            case "tinyint" -> info.addColumn(table, column, declared.startsWith("tinyint(1)") ? BOOLEAN : type);
            case "datetime" -> info.addColumn(table, column, DATETIME);
            case "timestamp" -> info.addColumn(table, column, TIMESTAMP);
            case "enum" -> registerEnum(info, table, column, columnType);
            default -> info.addColumn(table, column, type);
        }
    }

    /** Labels that are not GraphQL names cannot be enum values, so such a column stays a string. */
    private static void registerEnum(SchemaInfo info, String table, String column, String columnType) {
        info.addColumn(table, column, "varchar");
        Optional<List<String>> labels = enumLabels(columnType);
        if (labels.isEmpty() || !labels.get().stream().allMatch(MysqlColumnTypes::isEnumValueName)) {
            return;
        }
        String enumName = table + "_" + column + ENUM_SUFFIX;
        labels.get().forEach(label -> info.addEnumValue(enumName, label));
        info.addColumnEnumType(table, column, enumName);
    }

    private static boolean isEnumValueName(String label) {
        return GRAPHQL_NAME.matcher(label).matches() && !RESERVED_ENUM_VALUES.contains(label);
    }

    /** The labels of {@code enum('a','b')}, a quote inside a label written as two. */
    static Optional<List<String>> enumLabels(String columnType) {
        if (columnType == null || !columnType.toLowerCase(Locale.ROOT).startsWith("enum(")
                || !columnType.endsWith(")")) {
            return Optional.empty();
        }
        String body = columnType.substring("enum(".length(), columnType.length() - 1);
        List<String> labels = new ArrayList<>();
        StringBuilder label = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'' && quoted && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                label.append('\'');
                i++;
            } else if (c == '\'') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                labels.add(label.toString());
                label.setLength(0);
            } else if (quoted) {
                label.append(c);
            }
        }
        labels.add(label.toString());
        return Optional.of(List.copyOf(labels));
    }
}
