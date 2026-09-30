package io.github.excalibase.schema;

import java.util.List;
import java.util.Objects;

/**
 * A tracked function one role may call (docs/features/permissions.md §6), as its access plan decided.
 * It returns rows of {@code returnTable}, which the role may select, and those rows are filtered and
 * projected by that select permission wherever the function is called.
 *
 * @param function         schema-qualified name
 * @param fieldName        its GraphQL root field
 * @param arguments        what a client supplies, in declaration order; the session argument is not one
 * @param sessionArgument  the json/jsonb argument that receives the session variables, or null
 * @param securityDefiner  its body runs with its owner's database privileges
 */
public record ExposedFunction(String function, Operation operation, String fieldName, String returnTable,
                              boolean returnsSet, List<Argument> arguments, SessionArgument sessionArgument,
                              boolean securityDefiner) {

    /** QUERY for a STABLE/IMMUTABLE function, MUTATION for a VOLATILE one. */
    public enum Operation { QUERY, MUTATION }

    /** @param required it has no default, so a call must supply it */
    public record Argument(String name, String type, boolean required) {}

    public record SessionArgument(String name, String type) {}

    public ExposedFunction {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(operation, "operation");
        arguments = List.copyOf(arguments);
    }

    public String schema() {
        return function.substring(0, function.indexOf('.'));
    }

    public String name() {
        return function.substring(function.indexOf('.') + 1);
    }
}
