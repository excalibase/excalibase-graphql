package io.github.excalibase.config.ws;

import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Rewrites the column values of a rendered change: the row itself, or an UPDATE's {@code old}/{@code new}
 * images. Each value is passed with its column's type; a key that is neither a column nor an image is kept.
 */
final class ChangeImages {

    private static final String IMAGE_OLD = "old";
    private static final String IMAGE_NEW = "new";

    private ChangeImages() {
    }

    static Object mapColumns(Object rendered, String table, SchemaInfo view, BiFunction<Object, String, Object> format) {
        if (!(rendered instanceof Map<?, ?> row)) {
            return rendered;
        }
        Map<String, Object> written = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            String column = String.valueOf(key);
            String type = view.getColumnType(table, column);
            boolean image = (IMAGE_OLD.equals(column) || IMAGE_NEW.equals(column)) && value instanceof Map<?, ?>
                    && type == null;
            written.put(column, image ? mapColumns(value, table, view, format) : format.apply(value, type));
        });
        return written;
    }
}
