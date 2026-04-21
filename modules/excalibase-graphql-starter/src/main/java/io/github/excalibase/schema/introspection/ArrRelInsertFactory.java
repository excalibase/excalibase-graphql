package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLTypeReference;
import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.excalibase.schema.GraphqlConstants.CREATE_INPUT_SUFFIX;

/**
 * Builds the nested-insert wrapper input objects (one per unique child
 * type referenced via a reverse FK) used to drive Hasura-style
 * {@code data: [ChildCreateInput]} mutations.
 *
 * <p>The produced {@code TypeArrRelInsertInput} types reference the
 * corresponding {@code TypeCreateInput} by {@link GraphQLTypeReference}
 * so there is no build-order dependency on {@link CreateInputFactory}.
 */
public final class ArrRelInsertFactory {

    public Map<String, GraphQLInputObjectType> build(SchemaInfo schemaInfo) {
        Map<String, GraphQLInputObjectType> arrRelTypeMap = new LinkedHashMap<>();
        for (Map.Entry<String, SchemaInfo.ReverseFkInfo> revEntry : schemaInfo.getAllReverseFks().entrySet()) {
            String childTypeName = NamingHelpers.typeName(revEntry.getValue().childTable());
            String arrRelTypeName = childTypeName + "ArrRelInsertInput";
            // Deduplicate: reuse existing ArrRelInsertInput if the same child type
            // appears across multiple parents.
            arrRelTypeMap.computeIfAbsent(arrRelTypeName, name ->
                    GraphQLInputObjectType.newInputObject()
                            .name(name)
                            .field(GraphQLInputObjectField.newInputObjectField()
                                    .name("data")
                                    .type(GraphQLList.list(
                                            GraphQLTypeReference.typeRef(childTypeName + CREATE_INPUT_SUFFIX)))
                                    .build())
                            .build());
        }
        return arrRelTypeMap;
    }
}
