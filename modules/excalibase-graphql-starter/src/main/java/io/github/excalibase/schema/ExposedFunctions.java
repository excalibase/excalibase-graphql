package io.github.excalibase.schema;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The tracked functions one role may call; a function not held here does not exist for the role. */
public final class ExposedFunctions {

    public static final ExposedFunctions NONE = new ExposedFunctions(List.of());

    private final Map<String, ExposedFunction> byName = new LinkedHashMap<>();

    private ExposedFunctions(Collection<ExposedFunction> functions) {
        functions.forEach(function -> byName.put(function.function(), function));
    }

    public static ExposedFunctions of(Collection<ExposedFunction> functions) {
        return functions.isEmpty() ? NONE : new ExposedFunctions(functions);
    }

    /** The function served as the {@code operation} root field {@code fieldName}. */
    public Optional<ExposedFunction> field(String fieldName, ExposedFunction.Operation operation) {
        return byName.values().stream()
                .filter(function -> function.operation() == operation && function.fieldName().equals(fieldName))
                .findFirst();
    }

    /** The function of this schema-qualified name. */
    public Optional<ExposedFunction> named(String function) {
        return Optional.ofNullable(byName.get(function));
    }

    public Collection<ExposedFunction> all() {
        return List.copyOf(byName.values());
    }
}
