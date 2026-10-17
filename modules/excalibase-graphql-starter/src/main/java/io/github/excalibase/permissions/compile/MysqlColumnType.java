package io.github.excalibase.permissions.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A MySQL {@code COLUMN_TYPE}: {@code int unsigned}, {@code tinyint(1)}, {@code decimal(10,2)},
 * {@code enum('a','it''s')}. {@code base} is the type name without size or attributes.
 */
record MysqlColumnType(String base, String size, boolean unsigned, List<String> labels) {

    static MysqlColumnType parse(String columnType) {
        String trimmed = columnType.trim();
        int open = trimmed.indexOf('(');
        if (open < 0) {
            List<String> words = new ArrayList<>(List.of(trimmed.toLowerCase(Locale.ROOT).split("\\s+")));
            boolean unsigned = words.remove("unsigned");
            words.remove("zerofill");
            return new MysqlColumnType(String.join(" ", words), "", unsigned, List.of());
        }
        int close = trimmed.lastIndexOf(')');
        String base = trimmed.substring(0, open).trim().toLowerCase(Locale.ROOT);
        String inside = trimmed.substring(open + 1, Math.max(open + 1, close));
        boolean unsigned = close >= 0 && trimmed.substring(close + 1).toLowerCase(Locale.ROOT).contains("unsigned");
        return "enum".equals(base) ? new MysqlColumnType(base, "", unsigned, labels(inside))
                : new MysqlColumnType(base, inside.trim(), unsigned, List.of());
    }

    /** MySQL's boolean: {@code tinyint(1)}, signed. */
    boolean isBoolean() {
        return "tinyint".equals(base) && "1".equals(size) && !unsigned;
    }

    /** {@code 'a','it''s'} as its labels; a quote inside a label is doubled. */
    private static List<String> labels(String inside) {
        List<String> labels = new ArrayList<>();
        StringBuilder label = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < inside.length()) {
            char next = inside.charAt(i);
            boolean doubledQuote = quoted && next == '\'' && i + 1 < inside.length() && inside.charAt(i + 1) == '\'';
            if (doubledQuote) {
                label.append('\'');
            } else if (next == '\'') {
                quoted = !quoted;
                if (quoted) {
                    label.setLength(0);
                } else {
                    labels.add(label.toString());
                }
            } else if (quoted) {
                label.append(next);
            }
            i += doubledQuote ? 2 : 1;
        }
        return List.copyOf(labels);
    }
}
