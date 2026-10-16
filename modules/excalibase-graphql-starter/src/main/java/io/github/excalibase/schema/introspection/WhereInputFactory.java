package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import io.github.excalibase.FilterColumnKind;
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
                                    Map<String, GraphQLInputObjectType> enumFilters,
                                    FilterInputCatalog.FilterInputs filters) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLInputObjectType.Builder whereBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName + WHERE_INPUT_SUFFIX);
        for (String col : schemaInfo.getColumns(tableKey)) {
            GraphQLInputObjectType filter = filterForColumn(tableKey, col, schemaInfo, enumFilters, filters);
            if (filter != null) {
                whereBuilder.field(GraphQLInputObjectField.newInputObjectField().name(col).type(filter).build());
            }
        }
        return whereBuilder.build();
    }

    /** The column's filter type, or null when the database offers no operator on its kind. */
    private GraphQLInputObjectType filterForColumn(String table,
                                                   String col,
                                                   SchemaInfo schemaInfo,
                                                   Map<String, GraphQLInputObjectType> enumFilters,
                                                   FilterInputCatalog.FilterInputs filters) {
        FilterColumnKind kind = FilterColumns.kindOf(schemaInfo, table, col);
        return kind == FilterColumnKind.ENUM ? enumFilters.get(schemaInfo.getEnumType(table, col))
                : filters.forKind(kind);
    }
}
