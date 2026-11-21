package io.github.excalibase.mongodb;

import graphql.Scalars;
import graphql.scalars.ExtendedScalars;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Builds the GraphQL schema one caller is served: only declared collections, only the fields the caller may
 * see, and only the operators the field type supports. What is not in the schema cannot be asked for.
 */
final class GraphqlSchemas {

    static final String QUERY = "Query";
    static final String SORT_DIRECTION = "SortDirection";
    private static final Set<String> RESERVED = Set.of(QUERY, SORT_DIRECTION, "String", "Int", "Float", "Boolean", "ID",
            "Long", "StringFilter", "IdFilter", "IntFilter", "LongFilter", "FloatFilter", "BooleanFilter", "DateTimeFilter");

    private final Catalog catalog;
    private final MongoCapabilities capabilities;
    private final int maxRows;

    GraphqlSchemas(Catalog catalog, MongoCapabilities capabilities, int maxRows) {
        this.catalog = catalog;
        this.capabilities = capabilities;
        this.maxRows = maxRows;
        checkNames();
    }

    static String typeName(CollectionDef def) {
        return def.name().substring(0, 1).toUpperCase(Locale.ROOT) + def.name().substring(1);
    }

    static String byIdField(CollectionDef def) {
        return def.name() + "ById";
    }

    private void checkNames() {
        Set<String> taken = new HashSet<>(RESERVED);
        Set<String> roots = new HashSet<>();
        for (CollectionDef def : catalog.all()) {
            String type = typeName(def);
            boolean clash = !taken.add(type) || !taken.add(type + "Where") || !taken.add(type + "OrderBy")
                    || !taken.add(type + "Field") || !roots.add(def.name()) || !roots.add(byIdField(def));
            if (clash) {
                throw new IllegalStateException("Collection \"" + def.name() + "\" has a name that collides with another "
                        + "collection or a built-in GraphQL name");
            }
        }
    }

    GraphQLSchema build(MaskView view, DataFetcher<?> listFetcher, DataFetcher<?> byIdFetcher) {
        GraphQLCodeRegistry.Builder registry = GraphQLCodeRegistry.newCodeRegistry();
        GraphQLObjectType.Builder query = GraphQLObjectType.newObject().name(QUERY);
        Map<FieldKind, GraphQLInputObjectType> filters = new EnumMap<>(FieldKind.class);
        GraphQLEnumType direction = GraphQLEnumType.newEnum().name(SORT_DIRECTION).value("ASC").value("DESC").build();

        for (CollectionDef def : catalog.all()) {
            GraphQLObjectType object = objectType(def, view);
            GraphQLInputObjectType where = whereType(def, view, filters);
            GraphQLInputObjectType orderBy = orderByType(def, view, direction);
            query.field(GraphQLFieldDefinition.newFieldDefinition().name(def.name())
                    .type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(object))))
                    .argument(GraphQLArgument.newArgument().name("where").type(where))
                    .argument(GraphQLArgument.newArgument().name("orderBy").type(GraphQLList.list(GraphQLNonNull.nonNull(orderBy))))
                    .argument(GraphQLArgument.newArgument().name("limit").type(Scalars.GraphQLInt).defaultValueProgrammatic(maxRows))
                    .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValueProgrammatic(0)));
            query.field(GraphQLFieldDefinition.newFieldDefinition().name(byIdField(def)).type(object)
                    .argument(GraphQLArgument.newArgument().name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLID))));
            registry.dataFetcher(FieldCoordinates.coordinates(QUERY, def.name()), listFetcher);
            registry.dataFetcher(FieldCoordinates.coordinates(QUERY, byIdField(def)), byIdFetcher);
        }
        return GraphQLSchema.newSchema().query(query.build()).codeRegistry(registry.build()).build();
    }

    private static GraphQLObjectType objectType(CollectionDef def, MaskView view) {
        GraphQLObjectType.Builder object = GraphQLObjectType.newObject().name(typeName(def));
        for (String field : view.selectable(def)) {
            GraphQLOutputType type = outputType(def.kindOf(field));
            object.field(GraphQLFieldDefinition.newFieldDefinition().name(field)
                    .type(CollectionDef.ID_FIELD.equals(field) ? GraphQLNonNull.nonNull(type) : type));
        }
        return object.build();
    }

    private GraphQLInputObjectType whereType(CollectionDef def, MaskView view, Map<FieldKind, GraphQLInputObjectType> filters) {
        GraphQLInputObjectType.Builder where = GraphQLInputObjectType.newInputObject().name(typeName(def) + "Where");
        for (String field : view.filterable(def)) {
            FieldKind kind = def.kindOf(field);
            GraphQLInputObjectType filter = filters.computeIfAbsent(kind, this::filterType);
            where.field(GraphQLInputObjectField.newInputObjectField().name(field).type(filter));
        }
        return where.build();
    }

    private GraphQLInputObjectType filterType(FieldKind kind) {
        GraphQLInputType scalar = inputType(kind);
        GraphQLInputObjectType.Builder filter = GraphQLInputObjectType.newInputObject().name(filterName(kind));
        for (Op op : capabilities.operators(kind)) {
            GraphQLInputType type = switch (op) {
                case IN, NOT_IN -> GraphQLList.list(GraphQLNonNull.nonNull(scalar));
                case IS_NULL -> Scalars.GraphQLBoolean;
                default -> scalar;
            };
            filter.field(GraphQLInputObjectField.newInputObjectField().name(op.graphqlName()).type(type));
        }
        return filter.build();
    }

    private static GraphQLInputObjectType orderByType(CollectionDef def, MaskView view, GraphQLEnumType direction) {
        GraphQLEnumType.Builder fields = GraphQLEnumType.newEnum().name(typeName(def) + "Field");
        view.filterable(def).forEach(fields::value);
        return GraphQLInputObjectType.newInputObject().name(typeName(def) + "OrderBy")
                .field(GraphQLInputObjectField.newInputObjectField().name("field").type(GraphQLNonNull.nonNull(fields.build())))
                .field(GraphQLInputObjectField.newInputObjectField().name("direction").type(direction)
                        .defaultValueProgrammatic("ASC"))
                .build();
    }

    private static String filterName(FieldKind kind) {
        return switch (kind) {
            case STRING -> "StringFilter";
            case OBJECT_ID -> "IdFilter";
            case INT -> "IntFilter";
            case LONG -> "LongFilter";
            case DOUBLE -> "FloatFilter";
            case BOOLEAN -> "BooleanFilter";
            case DATETIME -> "DateTimeFilter";
        };
    }

    private static GraphQLScalarType scalar(FieldKind kind) {
        return switch (kind) {
            case STRING, DATETIME -> Scalars.GraphQLString;
            case OBJECT_ID -> Scalars.GraphQLID;
            case INT -> Scalars.GraphQLInt;
            case LONG -> ExtendedScalars.GraphQLLong;
            case DOUBLE -> Scalars.GraphQLFloat;
            case BOOLEAN -> Scalars.GraphQLBoolean;
        };
    }

    private static GraphQLOutputType outputType(FieldKind kind) {
        return scalar(kind);
    }

    private static GraphQLInputType inputType(FieldKind kind) {
        return scalar(kind);
    }
}
