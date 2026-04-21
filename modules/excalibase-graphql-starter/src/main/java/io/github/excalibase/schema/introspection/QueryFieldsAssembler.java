package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static io.github.excalibase.schema.GraphqlConstants.AGGREGATE_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.ARG_AFTER;
import static io.github.excalibase.schema.GraphqlConstants.ARG_BEFORE;
import static io.github.excalibase.schema.GraphqlConstants.ARG_FIRST;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LAST;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LIMIT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_OFFSET;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;
import static io.github.excalibase.schema.GraphqlConstants.CONNECTION_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.EDGE_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.EMPTY_FIELD;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_COUNT;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_CURSOR;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_EDGES;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_END_CURSOR;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_HAS_NEXT_PAGE;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_HAS_PREVIOUS_PAGE;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_NODE;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_PAGE_INFO;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_START_CURSOR;
import static io.github.excalibase.schema.GraphqlConstants.FIELD_TOTAL_COUNT;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_PAGE_INFO;

/**
 * Generates {@code list}, {@code connection} and {@code aggregate} query
 * fields per table. Each table contributes three fields to the Query type.
 */
public final class QueryFieldsAssembler {

    public List<GraphQLFieldDefinition> build(SchemaInfo schemaInfo,
                                       Map<String, GraphQLObjectType> tableTypes,
                                       Map<String, GraphQLInputObjectType> whereTypes) {
        List<GraphQLFieldDefinition> fields = new ArrayList<>();
        // GraphQL requires at least one field in Query — add placeholder when no tables exist.
        if (schemaInfo.getTableNames().isEmpty()) {
            fields.add(newFieldDefinition().name(EMPTY_FIELD).type(GraphQLString).build());
            return fields;
        }
        GraphQLObjectType pageInfoType = buildPageInfoType();
        for (String table : schemaInfo.getTableNames()) {
            String fName = NamingHelpers.fieldName(table);
            String tName = NamingHelpers.typeName(table);
            GraphQLObjectType type = tableTypes.get(table);
            GraphQLInputObjectType whereType = whereTypes.get(table);
            fields.add(buildListField(fName, type, whereType));
            fields.add(buildConnectionField(fName, tName, type, whereType, pageInfoType));
            fields.add(buildAggregateField(fName, tName));
        }
        return fields;
    }

    private GraphQLObjectType buildPageInfoType() {
        return newObject().name(TYPE_PAGE_INFO)
                .field(newFieldDefinition().name(FIELD_HAS_NEXT_PAGE).type(GraphQLBoolean).build())
                .field(newFieldDefinition().name(FIELD_HAS_PREVIOUS_PAGE).type(GraphQLBoolean).build())
                .field(newFieldDefinition().name(FIELD_START_CURSOR).type(GraphQLString).build())
                .field(newFieldDefinition().name(FIELD_END_CURSOR).type(GraphQLString).build())
                .build();
    }

    private GraphQLFieldDefinition buildListField(String fName,
                                                  GraphQLObjectType type,
                                                  GraphQLInputObjectType whereType) {
        return newFieldDefinition()
                .name(fName)
                .type(GraphQLList.list(type))
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                .argument(GraphQLArgument.newArgument().name(ARG_LIMIT).type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name(ARG_OFFSET).type(GraphQLInt).build())
                .build();
    }

    private GraphQLFieldDefinition buildConnectionField(String fName,
                                                        String tName,
                                                        GraphQLObjectType type,
                                                        GraphQLInputObjectType whereType,
                                                        GraphQLObjectType pageInfoType) {
        GraphQLObjectType edgeType = newObject().name(tName + EDGE_SUFFIX)
                .field(newFieldDefinition().name(FIELD_NODE).type(type).build())
                .field(newFieldDefinition().name(FIELD_CURSOR).type(GraphQLString).build())
                .build();
        GraphQLObjectType connectionType = newObject().name(tName + CONNECTION_SUFFIX)
                .field(newFieldDefinition().name(FIELD_EDGES).type(GraphQLList.list(edgeType)).build())
                .field(newFieldDefinition().name(FIELD_PAGE_INFO).type(pageInfoType).build())
                .field(newFieldDefinition().name(FIELD_TOTAL_COUNT).type(GraphQLInt).build())
                .build();
        return newFieldDefinition()
                .name(fName + CONNECTION_SUFFIX)
                .type(connectionType)
                .argument(GraphQLArgument.newArgument().name(ARG_FIRST).type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name(ARG_AFTER).type(GraphQLString).build())
                .argument(GraphQLArgument.newArgument().name(ARG_LAST).type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name(ARG_BEFORE).type(GraphQLString).build())
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereType).build())
                .build();
    }

    private GraphQLFieldDefinition buildAggregateField(String fName, String tName) {
        return newFieldDefinition()
                .name(fName + AGGREGATE_SUFFIX)
                .type(newObject().name(tName + AGGREGATE_SUFFIX)
                        .field(newFieldDefinition().name(FIELD_COUNT).type(GraphQLInt).build())
                        .build())
                .build();
    }
}
