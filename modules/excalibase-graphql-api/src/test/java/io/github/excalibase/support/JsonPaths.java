package io.github.excalibase.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small JSONPath subset the integration tests use: {@code $.a.b[0].c}, {@code [*]}, {@code ['name']} and
 * {@code [?(@.field == 'value')]}. A path with a wildcard or filter is indefinite and answers a list.
 */
final class JsonPaths {

    private static final Pattern STEP = Pattern.compile(
            "\\.(\\w+)|\\['([^']+)']|\\[(\\d+)]|\\[(\\*)]|\\[\\?\\(@\\.(\\w+)\\s*==\\s*(?:'([^']*)'|(\\S+?))\\)]");

    private JsonPaths() {
    }

    /** What the path selects: its Java value, or empty when a definite path leads nowhere. */
    record Selection(Optional<Object> value, boolean indefinite) {
    }

    static Selection select(JsonNode root, String path) {
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException("not a JSONPath: " + path);
        }
        if (path.endsWith(".length()")) {
            return length(select(root, path.substring(0, path.length() - ".length()".length())));
        }
        List<JsonNode> current = List.of(root);
        boolean indefinite = false;
        boolean missing = false;
        Matcher step = STEP.matcher(path.substring(1));
        int consumed = 0;
        while (step.find() && step.start() == consumed) {
            consumed = step.end();
            indefinite |= step.group(4) != null || step.group(5) != null;
            current = apply(current, step);
            missing |= current.isEmpty();
        }
        if (consumed != path.length() - 1) {
            throw new IllegalArgumentException("unsupported JSONPath: " + path);
        }
        if (indefinite) {
            return new Selection(Optional.of(current.stream().map(JsonPaths::toJava).toList()), true);
        }
        return new Selection(missing ? Optional.empty() : Optional.ofNullable(toJava(current.getFirst())), false);
    }

    private static Selection length(Selection selected) {
        Optional<Object> size = selected.value().map(JsonPaths::sizeOf);
        return new Selection(size, false);
    }

    private static Object sizeOf(Object value) {
        if (value instanceof List<?> list) {
            return list.size();
        }
        return value instanceof Map<?, ?> map ? map.size() : value.toString().length();
    }

    private static List<JsonNode> apply(List<JsonNode> nodes, Matcher step) {
        List<JsonNode> next = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (step.group(1) != null || step.group(2) != null) {
                addIfPresent(next, node.get(step.group(1) != null ? step.group(1) : step.group(2)));
            } else if (step.group(3) != null) {
                addIfPresent(next, node.get(Integer.parseInt(step.group(3))));
            } else if (step.group(4) != null) {
                node.forEach(next::add);
            } else {
                node.forEach(child -> addIfMatching(next, child, step.group(5),
                        step.group(6) != null ? step.group(6) : step.group(7)));
            }
        }
        return next;
    }

    private static void addIfPresent(List<JsonNode> into, JsonNode node) {
        if (node != null) {
            into.add(node);
        }
    }

    private static void addIfMatching(List<JsonNode> into, JsonNode candidate, String field, String expected) {
        JsonNode actual = candidate.get(field);
        if (actual != null && actual.asText().equals(expected)) {
            into.add(candidate);
        }
    }

    static Object toJava(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            node.properties().forEach(field -> object.put(field.getKey(), toJava(field.getValue())));
            return object;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(toJava(element)));
            return list;
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? (Object) node.intValue() : node.longValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        return node.isBoolean() ? (Object) node.booleanValue() : node.asText();
    }

    /** A JSON string holding a number matches that number, as a client reading the value as a number would. */
    private static boolean numericallyEqual(Number expected, String text) {
        try {
            return new BigDecimal(expected.toString()).compareTo(new BigDecimal(text)) == 0;
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        if (expected instanceof Number number && actual instanceof String text) {
            return numericallyEqual(number, text);
        }
        return expected == null ? actual == null : expected.equals(actual);
    }
}
