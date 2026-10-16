package io.github.excalibase.permissions.compile;

import io.github.excalibase.permissions.Literal;
import io.github.excalibase.permissions.PresetValue;
import io.github.excalibase.permissions.SessionVariable;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Map;

/** A permission's preset ({@code set}) value: checked against its column once, bound per request. */
public final class PresetValues {

    private static final SessionBinding NO_SESSION = new SessionBinding(Map.of());

    private PresetValues() {
    }

    /** @throws io.github.excalibase.permissions.PermissionEvaluationException unknown column or unfit literal */
    public static void check(PermissionDialect dialect, PresetValue value, String tableKey, String column,
                             SchemaInfo schema) {
        ColumnType type = ColumnType.of(dialect, schema, tableKey, column);
        if (value instanceof Literal literal && literal.value() != null) {
            NO_SESSION.value(literal, type);
        }
    }

    /** The value to write, as a JDBC bind value; a literal null writes NULL. */
    public static Object bind(PermissionDialect dialect, PresetValue value, String tableKey, String column,
                              SchemaInfo schema, SessionBinding binding) {
        ColumnType type = ColumnType.of(dialect, schema, tableKey, column);
        return switch (value) {
            case Literal literal when literal.value() == null -> null;
            case Literal literal -> type.writeValue(binding.value(literal, type));
            case SessionVariable variable -> type.writeValue(binding.value(variable, type));
        };
    }
}
