package io.github.excalibase.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Puts an object's fields in the order they were asked for. Both databases build rows as JSON values that
 * keep their own key order (Postgres jsonb and MySQL JSON sort keys by length), not the order selected.
 */
public final class JsonFieldOrder {

    private JsonFieldOrder() {
    }

    /** The order of a row's fields, and of the rows embedded in it under the key each is answered by. */
    public record Shape(List<String> keys, Map<String, Shape> embedded) {
        public static Shape flat(List<String> keys) {
            return new Shape(keys, Map.of());
        }
    }

    /** {@code node} in the order of {@code shape}, an embedded object or each of an embedded array's rows likewise. */
    public static JsonNode ordered(JsonNode node, Shape shape) {
        JsonNode row = ordered(node, shape.keys());
        if (!(row instanceof ObjectNode object)) {
            return row;
        }
        shape.embedded().forEach((key, inner) -> {
            JsonNode value = object.get(key);
            if (value instanceof ObjectNode) {
                object.set(key, ordered(value, inner));
            } else if (value != null && value.isArray()) {
                ArrayNode rows = JsonNodeFactory.instance.arrayNode();
                value.forEach(element -> rows.add(ordered(element, inner)));
                object.set(key, rows);
            }
        });
        return object;
    }

    /** A copy of {@code node} with {@code keys} first, in that order, then any other field as it was. */
    public static JsonNode ordered(JsonNode node, List<String> keys) {
        if (!(node instanceof ObjectNode object)) {
            return node;
        }
        ObjectNode ordered = JsonNodeFactory.instance.objectNode();
        for (String key : keys) {
            if (object.has(key)) {
                ordered.set(key, object.get(key));
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> fields = object.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!ordered.has(field.getKey())) {
                ordered.set(field.getKey(), field.getValue());
            }
        }
        return ordered;
    }
}
