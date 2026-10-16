package io.github.excalibase.rest.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.compiler.NumericInput;
import io.github.excalibase.errors.ColumnValueCheck;
import io.github.excalibase.schema.SchemaInfo;
import org.springframework.jdbc.core.SqlParameterValue;

import java.sql.Types;

/** The bind value of a request value, by its column's type. */
final class ParamValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SchemaInfo schemaInfo;
    private final boolean serverCasts;

    /** @param serverCasts whether the server parses a value bound untyped (Postgres); MySQL takes it as text */
    ParamValues(SchemaInfo schemaInfo, boolean serverCasts) {
        this.schemaInfo = schemaInfo;
        this.serverCasts = serverCasts;
    }

    Object coerceParam(String table, String column, Object value) {
        return bindable(typedParam(table, column, value));
    }

    Object convertValue(String value, String table, String column) {
        return bindable(typedValue(value, table, column));
    }

    private Object bindable(Object value) {
        return !serverCasts && value instanceof SqlParameterValue typed ? typed.getValue() : value;
    }

    /**
     * Coerce a value from a JSON body (Object) for binding.
     *
     * <p>Uses {@code Types.OTHER} for PostgreSQL-specific types so the server — not the
     * JDBC driver — performs the cast.  Timestamps accept many input formats (ISO-8601,
     * RFC-2822, "epoch", "now", etc.); binding as {@code Types.OTHER} sends the value as
     * an untyped text literal and lets PostgreSQL's input function resolve the format,
     * exactly like {@code '2026-05-01T14:00:00Z'::timestamptz}.
     */
    private Object typedParam(String table, String column, Object value) {
        if (value == null) return null;
        if (schemaInfo.getEnumType(table, column) != null) {
            return new SqlParameterValue(Types.OTHER, value.toString());
        }
        String type = schemaInfo.getColumnType(table, column);
        if (type == null) return value;
        return switch (type.toLowerCase()) {
            // Temporal types — with/without timezone, short aliases, array forms (_timestamp = pg ARRAY udt_name)
            case "timestamp", "timestamptz",
                 "timestamp with time zone", "timestamp without time zone",
                 "_timestamp", "_timestamptz", "timestamp[]", "timestamptz[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "date", "_date", "date[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "time", "timetz",
                 "time with time zone", "time without time zone",
                 "_time", "_timetz", "time[]", "timetz[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "interval", "_interval", "interval[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            // Other opaque server types
            case "uuid", "_uuid", "uuid[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            case "_text", "text[]" ->
                new SqlParameterValue(Types.OTHER, value.toString());
            // JSONB/JSON: Map/List from JSON body must be serialized to a JSON string first
            case "jsonb", "json", "_jsonb", "_json", "jsonb[]", "json[]" -> {
                String json = (value instanceof String stringValue) ? stringValue : toJsonString(value);
                yield new SqlParameterValue(Types.OTHER, json);
            }
            // Range types (int4range, ...) the server parses; a number sent as a string is bound as one.
            default -> type.endsWith("range") ? new SqlParameterValue(Types.OTHER, value.toString())
                    : NumericInput.coerce(type, value);
        };
    }

    private String toJsonString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException _) {
            return value.toString();
        }
    }

    /**
     * Coerce a filter value (String) for binding — converts to Java type for numeric columns,
     * delegates PostgreSQL-specific types to server-side casting via {@code Types.OTHER}.
     */
    private Object typedValue(String value, String table, String column) {
        if (schemaInfo.getEnumType(table, column) != null) {
            return new SqlParameterValue(Types.OTHER, value);
        }
        String type = schemaInfo.getColumnType(table, column);
        if (type == null) return value;
        ColumnValueCheck.require(table, column, type, value);
        try {
            return switch (type.toLowerCase()) {
                case "integer", "int4", "serial" -> Integer.parseInt(value);
                case "bigint", "int8", "bigserial" -> Long.parseLong(value);
                case "numeric", "decimal", "float8", "double precision" -> Double.parseDouble(value);
                case "real", "float4" -> Float.parseFloat(value);
                case "boolean", "bool" -> Boolean.parseBoolean(value);
                case "tinyint" -> tinyint(value);
                // Temporal types — server handles all format variations
                case "timestamp", "timestamptz",
                     "timestamp with time zone", "timestamp without time zone",
                     "_timestamp", "_timestamptz", "timestamp[]", "timestamptz[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "date", "_date", "date[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "time", "timetz",
                     "time with time zone", "time without time zone",
                     "_time", "_timetz", "time[]", "timetz[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "interval", "_interval", "interval[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                // Other opaque server types
                case "uuid", "_uuid", "uuid[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "jsonb", "json", "_jsonb", "_json", "jsonb[]", "json[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                case "_text", "text[]" ->
                    new SqlParameterValue(Types.OTHER, value);
                default -> value;
            };
        } catch (NumberFormatException _) {
            return value;
        }
    }

    /** MySQL's boolean is tinyint(1): true and false are 1 and 0, not text compared as a number. */
    private static Object tinyint(String value) {
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return value;
    }
}
