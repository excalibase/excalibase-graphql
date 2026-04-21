package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Builds GraphQL object types for Postgres composite types so they are
 * registered as {@code additionalType} on the schema and become
 * discoverable via {@code __type}.
 */
public final class CompositeTypeFactory {

    public List<GraphQLObjectType> build(SchemaInfo schemaInfo) {
        List<GraphQLObjectType> composites = new ArrayList<>();
        for (Map.Entry<String, List<SchemaInfo.CompositeTypeField>> entry : schemaInfo.getCompositeTypes().entrySet()) {
            String typeName = NamingHelpers.typeName(entry.getKey());
            GraphQLObjectType.Builder ctBuilder = newObject().name(typeName);
            for (SchemaInfo.CompositeTypeField field : entry.getValue()) {
                ctBuilder.field(newFieldDefinition()
                        .name(field.name())
                        .type(TypeMapping.mapColumnType(field.type()))
                        .build());
            }
            composites.add(ctBuilder.build());
        }
        return composites;
    }
}
