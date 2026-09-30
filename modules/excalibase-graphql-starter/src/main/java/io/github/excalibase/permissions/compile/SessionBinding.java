package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.Operand;
import io.github.excalibase.permissions.PermissionEvaluationException;
import io.github.excalibase.permissions.SessionVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One request's session variables (docs/features/permissions.md §2), and the binding of permission
 * operands to a column's type. A variable the request lacks, or a value the column's type cannot
 * hold, fails the request; nothing is ever defaulted.
 */
public final class SessionBinding {

    private final Map<String, String> variables;

    /** @param variables session variables by name, as {@code Principal.sessionVariables} returns them */
    public SessionBinding(Map<String, String> variables) {
        Map<String, String> lowered = new LinkedHashMap<>();
        variables.forEach((name, value) -> lowered.put(name.toLowerCase(Locale.ROOT), value));
        this.variables = Map.copyOf(lowered);
    }

    /** A scalar operand as the Java value of {@code type}. */
    public Object value(Operand operand, ColumnType type) {
        return switch (operand) {
            case Literal literal -> literalValue(literal.value(), type);
            case SessionVariable variable -> variableValue(variable, raw(variable), type);
        };
    }

    /**
     * An {@code _in}/{@code _nin} operand: a literal list, or a variable holding an array literal
     * ({@code {"a","b"}}) or a single value. Elements may be null (an unquoted {@code NULL}).
     */
    public List<Object> values(Operand operand, ColumnType type) {
        return switch (operand) {
            case Literal literal when literal.value() instanceof List<?> elements ->
                    elements.stream().map(element -> literalValue(element, type)).toList();
            case Literal literal -> List.of(literalValue(literal.value(), type));
            case SessionVariable variable -> variableValues(variable, type);
        };
    }

    private List<Object> variableValues(SessionVariable variable, ColumnType type) {
        String raw = raw(variable);
        if (!PgArrayLiteral.looksLikeArray(raw)) {
            return List.of(variableValue(variable, raw, type));
        }
        List<String> elements;
        try {
            elements = PgArrayLiteral.parse(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(variable);
        }
        List<Object> values = new ArrayList<>(elements.size());
        elements.forEach(element -> values.add(element == null ? null : variableValue(variable, element, type)));
        return Collections.unmodifiableList(values);
    }

    private String raw(SessionVariable variable) {
        String raw = variables.get(variable.name());
        if (raw == null) {
            throw new PermissionEvaluationException(PermissionEvaluationException.MISSING_SESSION_VARIABLE,
                    "The request has no session variable " + variable.name());
        }
        return raw;
    }

    private static Object variableValue(SessionVariable variable, String text, ColumnType type) {
        try {
            return type.canonical(text);
        } catch (IllegalArgumentException e) {
            throw invalid(variable);
        }
    }

    private static PermissionEvaluationException invalid(SessionVariable variable) {
        return new PermissionEvaluationException(PermissionEvaluationException.INVALID_SESSION_VARIABLE,
                "Session variable " + variable.name() + " does not fit the column's type");
    }

    private static Object literalValue(Object value, ColumnType type) {
        try {
            return type.canonical(value);
        } catch (IllegalArgumentException e) {
            throw new PermissionEvaluationException(PermissionEvaluationException.INVALID_LITERAL,
                    "A permission value does not fit the column's type");
        }
    }
}
