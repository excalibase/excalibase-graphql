package io.github.excalibase.config.ws;

import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the timestamp columns of a change the way REST reads them. The change stream carries Postgres's
 * text output ({@code 2026-10-07 10:00:00.123+00}); REST and GraphQL read rows through {@code row_to_json}
 * ({@code 2026-10-07T10:00:00.123+00:00}), so a client compares one value against the other unchanged.
 * Only columns typed timestamp are touched, an UPDATE's {@code old}/{@code new} images included.
 */
final class RestTimeFormat {

    private static final Pattern PG_TEXT = Pattern.compile(
            "(\\d{4,}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(?:([+-]\\d{2})(?::?(\\d{2}))?(?::?(\\d{2}))?)?");
    private static final String IMAGE_OLD = "old";
    private static final String IMAGE_NEW = "new";

    private RestTimeFormat() {
    }

    static Object apply(Object rendered, String table, SchemaInfo view) {
        if (!(rendered instanceof Map<?, ?> row)) {
            return rendered;
        }
        Map<String, Object> written = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            String column = String.valueOf(key);
            boolean image = (IMAGE_OLD.equals(column) || IMAGE_NEW.equals(column)) && value instanceof Map<?, ?>
                    && view.getColumnType(table, column) == null;
            written.put(column, image ? apply(value, table, view) : format(value, view.getColumnType(table, column)));
        });
        return written;
    }

    private static Object format(Object value, String type) {
        if (!(value instanceof String text) || type == null
                || !type.toLowerCase(Locale.ROOT).startsWith("timestamp")) {
            return value;
        }
        Matcher matcher = PG_TEXT.matcher(text);
        if (!matcher.matches()) {
            return value;
        }
        StringBuilder iso = new StringBuilder(matcher.group(1)).append('T').append(matcher.group(2));
        if (matcher.group(3) != null) {
            iso.append(matcher.group(3)).append(':').append(matcher.group(4) == null ? "00" : matcher.group(4));
            if (matcher.group(5) != null) {
                iso.append(':').append(matcher.group(5));
            }
        }
        return iso.toString();
    }
}
