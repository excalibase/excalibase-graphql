package io.github.excalibase.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.excalibase.SqlDialect;
import io.github.excalibase.schema.ExposedFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The call of a tracked function as a FROM source, shared by GraphQL and REST:
 * {@code "schema"."fn"("arg" => CAST(:p AS type), …)}. Arguments are passed by name and bound as
 * parameters; the session argument, when the function declares one, receives the request's session
 * variables as a JSON object and cannot be supplied by the client.
 */
public final class FunctionCall {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PARAM = "fn_arg_";

    private FunctionCall() {
    }

    /**
     * @param arguments what the client passed, by argument name; a value may be null
     * @param session   the request's session variables, keyed by lower-cased name
     * @throws IllegalArgumentException an unknown argument, or a required one missing
     */
    public static String sql(ExposedFunction function, Map<String, Object> arguments, Map<String, String> session,
                             SqlDialect dialect, Map<String, Object> params) {
        requireKnown(function, arguments);
        List<String> passed = new ArrayList<>();
        for (ExposedFunction.Argument argument : function.arguments()) {
            if (arguments.containsKey(argument.name())) {
                passed.add(named(argument.name(), argument.type(), bindable(arguments.get(argument.name())), dialect,
                        params));
            } else if (argument.required()) {
                throw new IllegalArgumentException("Missing argument " + argument.name() + " of " + function.function());
            }
        }
        ExposedFunction.SessionArgument sessionArgument = function.sessionArgument();
        if (sessionArgument != null) {
            passed.add(named(sessionArgument.name(), sessionArgument.type(), json(new TreeMap<>(session)), dialect,
                    params));
        }
        return dialect.qualifiedTable(function.schema(), function.name()) + "(" + String.join(", ", passed) + ")";
    }

    private static void requireKnown(ExposedFunction function, Map<String, Object> arguments) {
        for (String name : arguments.keySet()) {
            boolean known = function.arguments().stream().anyMatch(argument -> argument.name().equals(name));
            if (!known) {
                throw new IllegalArgumentException("Unknown argument " + name + " of " + function.function());
            }
        }
    }

    private static String named(String name, String type, Object value, SqlDialect dialect,
                                Map<String, Object> params) {
        String param = PARAM + params.size();
        params.put(param, value);
        return dialect.quoteIdentifier(name) + " => CAST(:" + param + " AS " + type + ")";
    }

    /** Objects and arrays travel as JSON text; the cast gives them the argument's type. */
    private static Object bindable(Object value) {
        return value instanceof Map<?, ?> || value instanceof List<?> ? json(value) : value;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Argument is not representable as JSON", e);
        }
    }
}
