package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Map;

import static io.github.excalibase.schema.GraphqlConstants.CREATE_INPUT_SUFFIX;

/**
 * Builds the per-table {@code TableNameCreateInput} input object with one
 * field per column plus one nested-insert {@code ArrRelInsertInput} field
 * per reverse FK. Enum-backed columns are typed with the matching
 * {@link GraphQLEnumType}; everything else falls through
 * {@link TypeMapping#mapInputType(String)}.
 */
public final class CreateInputFactory {

    public GraphQLInputObjectType buildFor(String tableKey,
                                    SchemaInfo schemaInfo,
                                    Map<String, GraphQLEnumType> enumTypes,
                                    Map<String, GraphQLInputObjectType> arrRelTypes) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLInputObjectType.Builder createBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName + CREATE_INPUT_SUFFIX);
        addColumnFields(tableKey, schemaInfo, enumTypes, createBuilder);
        addReverseFkFields(tableKey, schemaInfo, arrRelTypes, createBuilder);
        return createBuilder.build();
    }

    private void addColumnFields(String table,
                                 SchemaInfo schemaInfo,
                                 Map<String, GraphQLEnumType> enumTypes,
                                 GraphQLInputObjectType.Builder createBuilder) {
        for (String col : schemaInfo.getColumns(table)) {
            String enumTypeName = schemaInfo.getEnumType(table, col);
            GraphQLInputType inputType = enumTypeName != null && enumTypes.containsKey(enumTypeName)
                    ? enumTypes.get(enumTypeName)
                    : TypeMapping.mapInputType(schemaInfo.getColumnType(table, col));
            createBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col).type(inputType).build());
        }
    }

    // Reverse FK nested insert fields — each reverse relation exposed as an
    // ArrRelInsertInput child keyed on the child type name.
    private void addReverseFkFields(String table,
                                    SchemaInfo schemaInfo,
                                    Map<String, GraphQLInputObjectType> arrRelTypes,
                                    GraphQLInputObjectType.Builder createBuilder) {
        String prefix = table + ".";
        for (Map.Entry<String, SchemaInfo.ReverseFkInfo> revEntry : schemaInfo.getAllReverseFks().entrySet()) {
            if (!revEntry.getKey().startsWith(prefix)) continue;
            String revFieldName = revEntry.getKey().substring(prefix.length());
            String childTypeName = NamingHelpers.typeName(revEntry.getValue().childTable());
            GraphQLInputObjectType arrRelType = arrRelTypes.get(childTypeName + "ArrRelInsertInput");
            if (arrRelType != null) {
                createBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(revFieldName)
                        .type(arrRelType)
                        .build());
            }
        }
    }
}
