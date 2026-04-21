package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUTS;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;
import static io.github.excalibase.schema.GraphqlConstants.CALL_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * Generates {@code create}, {@code createMany}, {@code update}, {@code delete}
 * mutation fields per mutable table, plus {@code call<Proc>} fields for
 * stored procedures. Views are skipped (read-only).
 */
public final class MutationFieldsAssembler {

    public List<GraphQLFieldDefinition> build(SchemaInfo schemaInfo,
                                       Map<String, GraphQLObjectType> tableTypes,
                                       Map<String, GraphQLInputObjectType> createInputs,
                                       Map<String, GraphQLInputObjectType> whereTypes) {
        List<GraphQLFieldDefinition> fields = new ArrayList<>();
        for (String table : schemaInfo.getTableNames()) {
            // Skip views — they are read-only
            if (schemaInfo.isView(table)) continue;
            fields.addAll(buildCrudFields(table, tableTypes, createInputs, whereTypes));
        }
        for (Map.Entry<String, SchemaInfo.ProcedureInfo> procEntry : schemaInfo.getStoredProcedures().entrySet()) {
            fields.add(buildProcedureField(procEntry.getKey(), procEntry.getValue()));
        }
        return fields;
    }

    private List<GraphQLFieldDefinition> buildCrudFields(String table,
                                                         Map<String, GraphQLObjectType> tableTypes,
                                                         Map<String, GraphQLInputObjectType> createInputs,
                                                         Map<String, GraphQLInputObjectType> whereTypes) {
        String typeName = NamingHelpers.typeName(table);
        GraphQLObjectType type = tableTypes.get(table);
        GraphQLInputObjectType createInput = createInputs.get(table);
        GraphQLInputObjectType whereType = whereTypes.get(table);
        List<GraphQLFieldDefinition> crud = new ArrayList<>(4);
        crud.add(newFieldDefinition()
                .name(CREATE_PREFIX + typeName)
                .type(type)
                .argument(GraphQLArgument.newArgument().name(ARG_INPUT).type(createInput).build())
                .build());
        crud.add(newFieldDefinition()
                .name(CREATE_MANY_PREFIX + typeName)
                .type(GraphQLList.list(type))
                .argument(GraphQLArgument.newArgument().name(ARG_INPUTS).type(GraphQLList.list(createInput)).build())
                .build());
        crud.add(newFieldDefinition()
                .name(UPDATE_PREFIX + typeName)
                .type(GraphQLList.list(type))
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                .argument(GraphQLArgument.newArgument().name(ARG_INPUT).type(createInput).build())
                .build());
        crud.add(newFieldDefinition()
                .name(DELETE_PREFIX + typeName)
                .type(GraphQLList.list(type))
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                .build());
        return crud;
    }

    private GraphQLFieldDefinition buildProcedureField(String procName, SchemaInfo.ProcedureInfo proc) {
        String mutationName = CALL_PREFIX + NamingHelpers.typeName(procName);
        GraphQLFieldDefinition.Builder procField = newFieldDefinition()
                .name(mutationName)
                .type(GraphQLString); // Returns JSON string with OUT params
        for (SchemaInfo.ProcParam param : proc.inParams()) {
            GraphQLInputType argType = TypeMapping.mapInputType(param.type());
            procField.argument(GraphQLArgument.newArgument()
                    .name(param.name())
                    .type(argType)
                    .build());
        }
        return procField.build();
    }
}
