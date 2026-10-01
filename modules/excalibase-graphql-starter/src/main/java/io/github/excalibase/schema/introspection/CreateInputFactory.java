package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import static io.github.excalibase.schema.GraphqlConstants.CREATE_INPUT_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.UPDATE_INPUT_SUFFIX;

/**
 * Builds the per-table {@code TableNameCreateInput} input object with one
 * field per column plus one nested-insert field per insert relationship of the
 * table ({@code ArrRelInsertInput} or {@code ObjRelInsertInput}). Enum-backed columns are typed with the matching
 * {@link GraphQLEnumType}; everything else falls through
 * {@link TypeMapping#mapInputType(String)}.
 */
public final class CreateInputFactory {

    /** @param columns the columns the caller may set on insert */
    public GraphQLInputObjectType buildFor(String tableKey,
                                    Collection<String> columns,
                                    SchemaInfo schemaInfo,
                                    Map<String, GraphQLEnumType> enumTypes,
                                    Map<String, GraphQLInputObjectType> relationTypes) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLInputObjectType.Builder createBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName + CREATE_INPUT_SUFFIX);
        addColumnFields(tableKey, columns, schemaInfo, enumTypes, createBuilder);
        addRelationshipFields(tableKey, columns, schemaInfo, relationTypes, createBuilder);
        return createBuilder.build();
    }

    /** {@code TableNameUpdateInput}: the columns the caller may set on update, no nested inserts. */
    public GraphQLInputObjectType buildUpdateFor(String tableKey,
                                          Collection<String> columns,
                                          SchemaInfo schemaInfo,
                                          Map<String, GraphQLEnumType> enumTypes) {
        GraphQLInputObjectType.Builder updateBuilder = GraphQLInputObjectType.newInputObject()
                .name(NamingHelpers.typeName(tableKey) + UPDATE_INPUT_SUFFIX);
        addColumnFields(tableKey, columns, schemaInfo, enumTypes, updateBuilder);
        return updateBuilder.build();
    }

    private void addColumnFields(String table,
                                 Collection<String> columns,
                                 SchemaInfo schemaInfo,
                                 Map<String, GraphQLEnumType> enumTypes,
                                 GraphQLInputObjectType.Builder createBuilder) {
        for (String col : columns) {
            String enumTypeName = schemaInfo.getEnumType(table, col);
            GraphQLInputType inputType = enumTypeName != null && enumTypes.containsKey(enumTypeName)
                    ? enumTypes.get(enumTypeName)
                    : TypeMapping.mapInputType(schemaInfo.getColumnType(table, col));
            createBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col).type(inputType).build());
        }
    }

    /**
     * One field per relationship a nested insert may go through, typed by the wrapper of the other
     * table; a relationship named like one of the table's columns is left out, the column wins.
     */
    private void addRelationshipFields(String table,
                                       Collection<String> columns,
                                       SchemaInfo schemaInfo,
                                       Map<String, GraphQLInputObjectType> relationTypes,
                                       GraphQLInputObjectType.Builder createBuilder) {
        String prefix = table + ".";
        Map<String, String> fieldTypes = new TreeMap<>();
        schemaInfo.getArrayInsertRelationships().forEach((key, relation) -> {
            if (key.startsWith(prefix)) {
                fieldTypes.put(key.substring(prefix.length()), NamingHelpers.typeName(relation.childTable())
                        + RelationshipInsertFactory.ARRAY_SUFFIX);
            }
        });
        schemaInfo.getObjectInsertRelationships().forEach((key, relation) -> {
            if (key.startsWith(prefix)) {
                fieldTypes.put(key.substring(prefix.length()), NamingHelpers.typeName(relation.refTable())
                        + RelationshipInsertFactory.OBJECT_SUFFIX);
            }
        });
        fieldTypes.forEach((field, typeName) -> {
            GraphQLInputObjectType wrapper = relationTypes.get(typeName);
            if (wrapper != null && !columns.contains(field) && schemaInfo.getColumnType(table, field) == null) {
                createBuilder.field(GraphQLInputObjectField.newInputObjectField().name(field).type(wrapper).build());
            }
        });
    }
}
