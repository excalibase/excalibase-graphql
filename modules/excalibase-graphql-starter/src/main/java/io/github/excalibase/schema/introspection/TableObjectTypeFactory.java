package io.github.excalibase.schema.introspection;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeReference;
import io.github.excalibase.schema.SchemaInfo;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import static graphql.Scalars.GraphQLInt;
import static io.github.excalibase.schema.GraphqlConstants.AGGREGATE_SUFFIX;
import static io.github.excalibase.schema.GraphqlConstants.ARG_LIMIT;
import static io.github.excalibase.schema.GraphqlConstants.ARG_OFFSET;
import static io.github.excalibase.schema.GraphqlConstants.ARG_ORDER_BY;
import static io.github.excalibase.schema.GraphqlConstants.ARG_WHERE;
import static io.github.excalibase.schema.GraphqlConstants.WHERE_INPUT_SUFFIX;

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
        return buildFor(tableKey, schemaInfo, enumTypes, table -> false);
    }

    /** As above; an array relationship to a table in {@code aggregatable} also has its {@code …Aggregate} field. */
    public GraphQLObjectType buildFor(String tableKey,
                               SchemaInfo schemaInfo,
                               Map<String, GraphQLEnumType> enumTypes,
                               Predicate<String> aggregatable) {
        String typeName = NamingHelpers.typeName(tableKey);
        GraphQLObjectType.Builder typeBuilder = newObject().name(typeName);
        addScalarColumns(tableKey, schemaInfo, enumTypes, typeBuilder);
        addForwardFkFields(tableKey, schemaInfo, typeBuilder);
        addReverseFkFields(tableKey, schemaInfo, typeBuilder, aggregatable);
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

    // Forward FK fields: e.g., category_id → ShopifyCategories object, also under its alias
    private void addForwardFkFields(String table,
                                    SchemaInfo schemaInfo,
                                    GraphQLObjectType.Builder typeBuilder) {
        String prefix = table + ".";
        for (Map.Entry<String, SchemaInfo.FkInfo> fkEntry : new TreeMap<>(schemaInfo.getAllForwardFks()).entrySet()) {
            if (!fkEntry.getKey().startsWith(prefix)) continue;
            String fkFieldName = fkEntry.getKey().substring(prefix.length());
            GraphQLTypeReference refType = GraphQLTypeReference.typeRef(
                    NamingHelpers.typeName(fkEntry.getValue().refTable()));
            addRelationshipField(typeBuilder, table, fkFieldName, schemaInfo,
                    name -> newFieldDefinition().name(name).type(refType));
        }
    }

    // Reverse FK fields: e.g., products → [ShopifyProductVariants] list, with the list arguments
    private void addReverseFkFields(String table,
                                    SchemaInfo schemaInfo,
                                    GraphQLObjectType.Builder typeBuilder,
                                    Predicate<String> aggregatable) {
        String prefix = table + ".";
        for (Map.Entry<String, SchemaInfo.ReverseFkInfo> revEntry : new TreeMap<>(schemaInfo.getAllReverseFks()).entrySet()) {
            if (!revEntry.getKey().startsWith(prefix)) continue;
            String revFieldName = revEntry.getKey().substring(prefix.length());
            String childTable = revEntry.getValue().childTable();
            addRelationshipField(typeBuilder, table, revFieldName, schemaInfo,
                    name -> listField(name, childTable, schemaInfo));
            if (aggregatable.test(childTable)) {
                String aggregateName = revFieldName + AGGREGATE_SUFFIX;
                String alias = schemaInfo.getRelationshipAlias(table, revFieldName);
                for (String name : alias == null ? List.of(aggregateName) : List.of(alias + AGGREGATE_SUFFIX, aggregateName)) {
                    typeBuilder.field(aggregateField(name, childTable));
                }
            }
        }
    }

    /** {@code <relationship>Aggregate}: the child table's aggregate over the related rows, with its where. */
    private static GraphQLFieldDefinition aggregateField(String name, String childTable) {
        String childTypeName = NamingHelpers.typeName(childTable);
        return newFieldDefinition()
                .name(name)
                .type(GraphQLTypeReference.typeRef(childTypeName + AGGREGATE_SUFFIX))
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE)
                        .type(GraphQLTypeReference.typeRef(childTypeName + WHERE_INPUT_SUFFIX)).build())
                .build();
    }

    /** A nested list takes the arguments of a table's list: where, orderBy, limit and offset. */
    private static GraphQLFieldDefinition.Builder listField(String name, String childTable, SchemaInfo schemaInfo) {
        String childTypeName = NamingHelpers.typeName(childTable);
        GraphQLFieldDefinition.Builder field = newFieldDefinition()
                .name(name)
                .type(GraphQLList.list(GraphQLTypeReference.typeRef(childTypeName)))
                .argument(GraphQLArgument.newArgument().name(ARG_WHERE)
                        .type(GraphQLTypeReference.typeRef(childTypeName + WHERE_INPUT_SUFFIX)).build());
        if (!schemaInfo.getColumns(childTable).isEmpty()) {
            field.argument(GraphQLArgument.newArgument().name(ARG_ORDER_BY)
                    .type(GraphQLTypeReference.typeRef(OrderByInputFactory.typeName(childTable))).build());
        }
        return field
                .argument(GraphQLArgument.newArgument().name(ARG_LIMIT).type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name(ARG_OFFSET).type(GraphQLInt).build());
    }

    /**
     * The relationship under its own name and, when it has one, under its alias; the own name is then
     * deprecated in the alias's favour.
     */
    private static void addRelationshipField(GraphQLObjectType.Builder typeBuilder, String table, String name,
                                             SchemaInfo schemaInfo,
                                             Function<String, GraphQLFieldDefinition.Builder> field) {
        String alias = schemaInfo.getRelationshipAlias(table, name);
        if (alias == null) {
            typeBuilder.field(field.apply(name).build());
            return;
        }
        typeBuilder.field(field.apply(alias).build());
        typeBuilder.field(field.apply(name).deprecate("Use " + alias).build());
    }
}
