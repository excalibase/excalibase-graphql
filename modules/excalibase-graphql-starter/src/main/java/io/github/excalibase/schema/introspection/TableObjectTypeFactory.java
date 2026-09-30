package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeReference;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Map;

import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * Builds the per-table GraphQL {@link GraphQLObjectType} that appears as
 * the read model: scalar columns, computed fields, forward-FK fields
 * (typed as {@link GraphQLTypeReference} to break cycles) and reverse-FK
 * list fields. Reference-only — no resolvers are attached.
 */
public final class TableObjectTypeFactory {

    public GraphQLObjectType buildFor(String tableKey,
                               SchemaInfo schemaInfo,
                               Map<String, GraphQLEnumType> enumTypes) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLObjectType.Builder typeBuilder = newObject().name(typeName);
        addScalarColumns(tableKey, schemaInfo, enumTypes, typeBuilder);
        addForwardFkFields(tableKey, schemaInfo, typeBuilder);
        addReverseFkFields(tableKey, schemaInfo, typeBuilder);
        return typeBuilder.build();
    }

    private void addScalarColumns(String table,
                                  SchemaInfo schemaInfo,
                                  Map<String, GraphQLEnumType> enumTypes,
                                  GraphQLObjectType.Builder typeBuilder) {
        for (String col : schemaInfo.getColumns(table)) {
            String enumTypeName = schemaInfo.getEnumType(table, col);
            GraphQLOutputType colType = enumTypeName != null && enumTypes.containsKey(enumTypeName)
                    ? enumTypes.get(enumTypeName)
                    : TypeMapping.mapColumnType(schemaInfo.getColumnType(table, col));
            typeBuilder.field(newFieldDefinition()
                    .name(col)
                    .type(colType)
                    .build());
        }
    }

    // Forward FK fields: e.g., category_id → ShopifyCategories object
    private void addForwardFkFields(String table,
                                    SchemaInfo schemaInfo,
                                    GraphQLObjectType.Builder typeBuilder) {
        String prefix = table + ".";
        for (Map.Entry<String, SchemaInfo.FkInfo> fkEntry : schemaInfo.getAllForwardFks().entrySet()) {
            if (!fkEntry.getKey().startsWith(prefix)) continue;
            String fkFieldName = fkEntry.getKey().substring(prefix.length());
            String refTypeName = NamingHelpers.typeName(fkEntry.getValue().refTable());
            typeBuilder.field(newFieldDefinition()
                    .name(fkFieldName)
                    .type(GraphQLTypeReference.typeRef(refTypeName))
                    .build());
        }
    }

    // Reverse FK fields: e.g., products → [ShopifyProductVariants] list
    private void addReverseFkFields(String table,
                                    SchemaInfo schemaInfo,
                                    GraphQLObjectType.Builder typeBuilder) {
        String prefix = table + ".";
        for (Map.Entry<String, SchemaInfo.ReverseFkInfo> revEntry : schemaInfo.getAllReverseFks().entrySet()) {
            if (!revEntry.getKey().startsWith(prefix)) continue;
            String revFieldName = revEntry.getKey().substring(prefix.length());
            String childTypeName = NamingHelpers.typeName(revEntry.getValue().childTable());
            typeBuilder.field(newFieldDefinition()
                    .name(revFieldName)
                    .type(GraphQLList.list(GraphQLTypeReference.typeRef(childTypeName)))
                    .build());
        }
    }
}
