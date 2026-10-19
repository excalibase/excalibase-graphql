package io.github.excalibase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Postgres array columns: which column types are arrays, their element type, and a request value as the
 * array literal ({@code {"a","b"}}) the server parses into the column's own array type.
 */
public final class ArrayColumns {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArrayColumns() {
    }

    /** True for an array type as the schema loader records it: the udt name {@code _text}, or {@code text[]}. */
    public static boolean isArray(String columnType) {
        return columnType != null && (columnType.startsWith("_") || columnType.endsWith("[]"));
    }

    /** The element type of an array type: {@code _int4} and {@code int4[]} are both {@code int4}. */
    public static String elementType(String columnType) {
        if (columnType.endsWith("[]")) return columnType.substring(0, columnType.length() - 2);
        return columnType.startsWith("_") ? columnType.substring(1) : columnType;
    }

    /**
     * {@code value} as an array literal: a list, or the JSON text of one (an inline GraphQL list arrives as
     * JSON), becomes {@code {...}}; anything else, such as a literal already in Postgres form, is unchanged.
     */
    public static Object literal(Object value) {
        if (value instanceof Collection<?> items) return literalOf(items);
        if (value instanceof String text && text.trim().startsWith("[")) {
            try {
                return literalOf(MAPPER.readValue(text, List.class));
            } catch (JsonProcessingException _) {
                return value; // not JSON: the database refuses it with its own reason
            }
        }
        return value;
    }

    private static String literalOf(Collection<?> items) {
        StringBuilder literal = new StringBuilder("{");
        boolean first = true;
        for (Object item : items) {
            if (!first) literal.append(',');
            first = false;
            if (item == null) {
                literal.append("NULL");
            } else if (item instanceof Collection<?> nested) {
                literal.append(literalOf(nested));
            } else {
                literal.append(quoted(text(item)));
            }
        }
        return literal.append('}').toString();
    }

    private static String text(Object item) {
        if (!(item instanceof Map<?, ?>)) return String.valueOf(item);
        try {
            return MAPPER.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Array element cannot be written as JSON", e);
        }
    }

    private static String quoted(String element) {
        return '"' + element.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
