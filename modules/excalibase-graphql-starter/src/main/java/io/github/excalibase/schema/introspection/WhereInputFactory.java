package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.Map;

import static io.github.excalibase.schema.GraphqlConstants.WHERE_INPUT_SUFFIX;

/**
 * Builds the per-table {@code TableNameWhereInput} input object by selecting
 * the right filter sub-type for each column based on its database type or
 * enum binding.
 */
public final class WhereInputFactory {

    public GraphQLInputObjectType buildFor(String tableKey,
                                    SchemaInfo schemaInfo,
                                    Map<String, GraphQLEnumType> enumTypes,
                                    Map<String, GraphQLInputObjectType> enumFilters,
                                    FilterInputCatalog.FilterInputs filters) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLInputObjectType.Builder whereBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName + WHERE_INPUT_SUFFIX);
        for (String col : schemaInfo.getColumns(tableKey)) {
            GraphQLInputObjectType filter = filterForColumn(tableKey, col, schemaInfo, enumTypes, enumFilters, filters);
            whereBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col).type(filter).build());
        }
        return whereBuilder.build();
    }

    private GraphQLInputObjectType filterForColumn(String table,
                                                   String col,
                                                   SchemaInfo schemaInfo,
                                                   Map<String, GraphQLEnumType> enumTypes,
                                                   Map<String, GraphQLInputObjectType> enumFilters,
                                                   FilterInputCatalog.FilterInputs filters) {
        String colType = schemaInfo.getColumnType(table, col);
        String enumTypeName = schemaInfo.getEnumType(table, col);
        if (enumTypeName != null && enumFilters.containsKey(enumTypeName) && enumTypes.containsKey(enumTypeName)) {
            // Enum-backed column — use the per-enum filter so clients
            // get narrowed operators (eq accepts only enum members).
            return enumFilters.get(enumTypeName);
        }
        if (TypeMapping.isJsonType(colType)) return filters.jsonFilter();
        if (TypeMapping.isBooleanType(colType)) return filters.booleanFilter();
        if (TypeMapping.isDateTimeType(colType)) return filters.dateTimeFilter();
        if (TypeMapping.isFloatType(colType)) return filters.floatFilter();
        if (TypeMapping.isIntegerType(colType)) return filters.intFilter();
        if ("tsvector".equalsIgnoreCase(colType)) return filters.tsvectorFilter();
        return filters.stringFilter();
    }
}
