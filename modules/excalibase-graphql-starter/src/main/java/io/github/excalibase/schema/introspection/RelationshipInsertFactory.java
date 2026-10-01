package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLTypeReference;
import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.excalibase.schema.GraphqlConstants.CREATE_INPUT_SUFFIX;

/**
 * Builds the wrapper inputs a nested insert goes through, Hasura's {@code <table>_arr_rel_insert_input}
 * ({@code data: [ChildCreateInput!]!}) and {@code <table>_obj_rel_insert_input}
 * ({@code data: ParentCreateInput!}), one per table some insert relationship reaches. The row inputs
 * are referenced by name, so there is no build-order dependency on {@link CreateInputFactory}.
 */
public final class RelationshipInsertFactory {

    public static final String ARRAY_SUFFIX = "ArrRelInsertInput";
    public static final String OBJECT_SUFFIX = "ObjRelInsertInput";

    /** Keyed by type name; empty when the store takes no nested inserts. */
    public Map<String, GraphQLInputObjectType> build(SchemaInfo schemaInfo, boolean nestedInserts) {
        Map<String, GraphQLInputObjectType> types = new LinkedHashMap<>();
        if (!nestedInserts) {
            return types;
        }
        schemaInfo.getArrayInsertRelationships().values().forEach(relation -> {
            String rowType = NamingHelpers.typeName(relation.childTable()) + CREATE_INPUT_SUFFIX;
            types.computeIfAbsent(NamingHelpers.typeName(relation.childTable()) + ARRAY_SUFFIX, name ->
                    wrapper(name, GraphQLNonNull.nonNull(GraphQLList.list(
                            GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(rowType))))));
        });
        schemaInfo.getObjectInsertRelationships().values().forEach(relation -> {
            String rowType = NamingHelpers.typeName(relation.refTable()) + CREATE_INPUT_SUFFIX;
            types.computeIfAbsent(NamingHelpers.typeName(relation.refTable()) + OBJECT_SUFFIX, name ->
                    wrapper(name, GraphQLNonNull.nonNull(GraphQLTypeReference.typeRef(rowType))));
        });
        return types;
    }

    private static GraphQLInputObjectType wrapper(String name, GraphQLInputType data) {
        return GraphQLInputObjectType.newInputObject()
                .name(name)
                .field(GraphQLInputObjectField.newInputObjectField().name("data").type(data).build())
                .build();
    }
}
