package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.ExposedFunction;
import io.github.excalibase.schema.ExposedFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLInt;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LIMIT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_OFFSET;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;

/**
 * A root field per tracked function the caller may call: its arguments (required unless they have a
 * default; never the session argument), and the return table's type, a list for a set-returning
 * function, one nullable object otherwise. A list also takes the table's row arguments.
 */
public final class FunctionFieldsAssembler {

    public List<GraphQLFieldDefinition> build(ExposedFunctions functions, ExposedFunction.Operation operation,
                                              Map<String, GraphQLObjectType> tableTypes,
                                              Map<String, GraphQLInputObjectType> whereTypes) {
        List<GraphQLFieldDefinition> fields = new ArrayList<>();
        for (ExposedFunction function : functions.all()) {
            GraphQLObjectType type = tableTypes.get(function.returnTable());
            if (function.operation() == operation && type != null) {
                fields.add(field(function, type, whereTypes.get(function.returnTable())));
            }
        }
        return fields;
    }

    private GraphQLFieldDefinition field(ExposedFunction function, GraphQLObjectType type,
                                         GraphQLInputObjectType whereType) {
        GraphQLFieldDefinition.Builder field = newFieldDefinition()
                .name(function.fieldName())
                .description(description(function))
                .type(function.returnsSet() ? GraphQLList.list(type) : type);
        for (ExposedFunction.Argument argument : function.arguments()) {
            GraphQLInputType argumentType = TypeMapping.mapInputType(argument.type());
            field.argument(GraphQLArgument.newArgument()
                    .name(argument.name())
                    .type(argument.required() ? GraphQLNonNull.nonNull(argumentType) : argumentType)
                    .build());
        }
        field.argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build());
        if (function.returnsSet()) {
            field.argument(GraphQLArgument.newArgument().name(ARG_LIMIT).type(GraphQLInt).build());
            field.argument(GraphQLArgument.newArgument().name(ARG_OFFSET).type(GraphQLInt).build());
        }
        return field.build();
    }

    private static String description(ExposedFunction function) {
        String privileges = function.securityDefiner()
                ? "Its body runs with the database privileges of its owner (SECURITY DEFINER)."
                : "Its body runs with the database privileges of the engine's connection.";
        return "Calls " + function.function() + "; rows are limited to what the caller may select on "
                + function.returnTable() + ". " + privileges;
    }
}
