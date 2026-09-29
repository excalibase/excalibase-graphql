package io.github.excalibase.permissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A literal value as plain Java: {@code null}, {@link Boolean}, {@link String}, {@link Long} or
 * {@link java.math.BigDecimal}, or an unmodifiable {@link List}/{@link Map} of those.
 */
public record Literal(Object value) implements Operand, PresetValue {

    public Literal {
        value = frozen(value);
    }

    private static Object frozen(Object value) {
        if (value instanceof List<?> elements) {
            List<Object> copy = new ArrayList<>();
            elements.forEach(element -> copy.add(frozen(element)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> entries) {
            Map<String, Object> copy = new LinkedHashMap<>();
            entries.forEach((key, element) -> copy.put(String.valueOf(key), frozen(element)));
            return Collections.unmodifiableMap(copy);
        }
        return value;
    }
}
