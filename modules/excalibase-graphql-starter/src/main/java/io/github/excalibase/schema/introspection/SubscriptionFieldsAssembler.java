package io.github.excalibase.schema.introspection;

import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import io.github.excalibase.schema.SchemaInfo;

import java.util.ArrayList;
import java.util.List;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * One {@code <table>Changes} subscription per table the role can read, as the websocket handler serves
 * them: each change's operation, table, row ({@code data}, JSON, already cut to the role's columns) and
 * the time it happened as an ISO-8601 string. Views have no change stream, so no subscription.
 */
public final class SubscriptionFieldsAssembler {

    public static final String CHANGES_SUFFIX = "Changes";
    private static final GraphQLObjectType CHANGE_EVENT = newObject().name("ChangeEvent")
            .field(newFieldDefinition().name("operation").type(GraphQLString).build())
            .field(newFieldDefinition().name("table").type(GraphQLString).build())
            .field(newFieldDefinition().name("data").type(ExtendedScalars.Json).build())
            .field(newFieldDefinition().name("timestamp").type(GraphQLString).build())
            .build();

    public List<GraphQLFieldDefinition> build(SchemaInfo schemaInfo) {
        List<GraphQLFieldDefinition> fields = new ArrayList<>();
        for (String table : schemaInfo.getTableNames()) {
            if (!schemaInfo.isView(table)) {
                fields.add(newFieldDefinition().name(NamingHelpers.fieldName(table) + CHANGES_SUFFIX)
                        .type(CHANGE_EVENT).build());
            }
        }
        return fields;
    }
}
