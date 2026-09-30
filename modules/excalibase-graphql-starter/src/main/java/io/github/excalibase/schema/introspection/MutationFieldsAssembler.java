package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.SchemaInfo;
import io.github.excalibase.schema.TableAccess;
import io.github.excalibase.security.RlsOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_INPUTS;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_MANY_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.CREATE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.DELETE_PREFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_PREFIX;

/**
 * Generates {@code create}, {@code createMany}, {@code update}, {@code delete}
 * mutation fields per mutable table. Views are skipped (read-only).
 */
public final class MutationFieldsAssembler {

    /** The caller's insert inputs, update inputs and where inputs, keyed by table. */
    public record Inputs(Map<String, GraphQLInputObjectType> create,
                         Map<String, GraphQLInputObjectType> update,
                         Map<String, GraphQLInputObjectType> where) {}

    public List<GraphQLFieldDefinition> build(SchemaInfo schemaInfo,
                                       Map<String, GraphQLObjectType> tableTypes,
                                       Inputs inputs,
                                       TableAccess access) {
        List<GraphQLFieldDefinition> fields = new ArrayList<>();
        for (String table : schemaInfo.getTableNames()) {
            // Skip views — they are read-only
            if (schemaInfo.isView(table)) continue;
            fields.addAll(buildCrudFields(table, tableTypes, inputs, access));
        }
        return fields;
    }

    /**
     * Emits only the mutations the caller holds: an operation it has no permission for is absent
     * from the schema rather than refused at execution time.
     */
    private List<GraphQLFieldDefinition> buildCrudFields(String table,
                                                         Map<String, GraphQLObjectType> tableTypes,
                                                         Inputs inputs,
                                                         TableAccess access) {
        String typeName = NamingHelpers.typeName(table);
        GraphQLObjectType type = tableTypes.get(table);
        GraphQLInputObjectType whereType = inputs.where().get(table);
        List<GraphQLFieldDefinition> crud = new ArrayList<>(4);
        if (access.permits(table, RlsOp.INSERT)) {
            GraphQLInputObjectType createInput = inputs.create().get(table);
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
        }
        if (access.permits(table, RlsOp.UPDATE)) {
            crud.add(newFieldDefinition()
                    .name(UPDATE_PREFIX + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_INPUT).type(inputs.update().get(table)).build())
                    .build());
        }
        if (access.permits(table, RlsOp.DELETE)) {
            crud.add(newFieldDefinition()
                    .name(DELETE_PREFIX + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                    .build());
        }
        return crud;
    }
}
