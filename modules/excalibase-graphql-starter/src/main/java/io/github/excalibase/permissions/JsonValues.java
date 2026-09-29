package io.github.excalibase.permissions;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared JSON reading for the permission parsers; every failure names where in the document it is. */
final class JsonValues {

    /** A column or relationship name: a GraphQL field name, plus {@code $} as Postgres allows. */
    static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]{0,62}$");

    /** A schema-qualified, lower-case table or function name (contract: quoted identifiers are refused). */
    static final Pattern QUALIFIED_NAME = Pattern.compile("^[a-z_][a-z0-9_$]{0,62}\\.[a-z_][a-z0-9_$]{0,62}$");

    private JsonValues() {
    }

    static PermissionDocumentException fail(String path, String problem) {
        return new PermissionDocumentException(path + ": " + problem);
    }

    static JsonNode object(JsonNode node, String path, Set<String> allowedKeys) {
        if (node == null || !node.isObject()) {
            throw fail(path, "must be an object");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowedKeys.contains(entry.getKey())) {
                throw fail(path, "unknown key \"" + entry.getKey() + "\"");
            }
        }
        return node;
    }

    static JsonNode array(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isArray()) {
            throw fail(path + "." + key, "must be an array");
        }
        return node;
    }

    static String text(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw fail(path + "." + key, "must be a non-empty string");
        }
        return node.asText();
    }

    static String matching(JsonNode parent, String key, String path, Pattern pattern) {
        String value = text(parent, key, path);
        if (!pattern.matcher(value).matches()) {
            throw fail(path + "." + key, "is not a valid name");
        }
        return value;
    }

    static boolean bool(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null || !node.isBoolean()) {
            throw fail(path + "." + key, "must be true or false");
        }
        return node.booleanValue();
    }

    /** A JSON scalar other than null, as plain Java. */
    static boolean isScalar(JsonNode node) {
        return node.isTextual() || node.isNumber() || node.isBoolean();
    }

    /** Any JSON value as plain Java: numbers become Long when they fit, else BigDecimal. */
    static Object plain(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return number(node);
        }
        if (node.isArray()) {
            List<Object> elements = new ArrayList<>();
            node.forEach(element -> elements.add(plain(element)));
            return elements;
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        node.properties().forEach(entry -> entries.put(entry.getKey(), plain(entry.getValue())));
        return entries;
    }

    private static Object number(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.longValue() : new BigDecimal(node.bigIntegerValue());
        }
        return node.decimalValue();
    }
}
