package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.excalibase.schema.GraphqlConstants.ORDER_BY_INPUT_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_ORDER_DIRECTION;

/**
 * The per-table {@code TableNameOrderByInput} a list's {@code orderBy} takes: one field per readable
 * column, each an {@code OrderDirection}.
 */
public final class OrderByInputFactory {

    private static final List<String> DIRECTIONS = List.of("ASC", "DESC", "AscNullsFirst", "AscNullsLast",
            "DescNullsFirst", "DescNullsLast");

    /** The order input of every readable table that has a column, by table key. */
    public Map<String, GraphQLInputObjectType> build(SchemaInfo schemaInfo) {
        GraphQLEnumType.Builder direction = GraphQLEnumType.newEnum().name(TYPE_ORDER_DIRECTION);
        DIRECTIONS.forEach(direction::value);
        GraphQLEnumType directionType = direction.build();
        Map<String, GraphQLInputObjectType> types = new LinkedHashMap<>();
        for (String table : schemaInfo.getTableNames()) {
            if (schemaInfo.getColumns(table).isEmpty()) continue;
            GraphQLInputObjectType.Builder input = GraphQLInputObjectType.newInputObject()
                    .name(typeName(table));
            schemaInfo.getColumns(table).forEach(column -> input.field(GraphQLInputObjectField.newInputObjectField()
                    .name(column).type(directionType).build()));
            types.put(table, input.build());
        }
        return types;
    }

    /** The name of {@code table}'s order input. */
    static String typeName(String table) {
        return NamingHelpers.typeName(table) + ORDER_BY_INPUT_SUFFIX;
    }
}
