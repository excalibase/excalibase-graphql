package io.github.excalibase.mysql.generator;

import graphql.Scalars;
import graphql.schema.*;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.scalar.JsonScalar;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static graphql.Scalars.*;

/**
 * MySQL implementation of {@link IGraphQLSchemaGenerator}.
 *
 * <p>Generates a complete GraphQL schema from MySQL table metadata. MySQL-specific
 * type mapping:</p>
 * <ul>
 *   <li>{@code int}, {@code bigint}, {@code smallint}, {@code mediumint}, {@code tinyint} → {@code Int}</li>
 *   <li>{@code varchar}, {@code char}, {@code text*}, {@code enum} → {@code String}</li>
 *   <li>{@code decimal}, {@code float}, {@code double} → {@code Float}</li>
 *   <li>{@code datetime}, {@code timestamp}, {@code date}, {@code time}, {@code year} → {@code String}</li>
 *   <li>{@code json} → {@code JSON} scalar</li>
 *   <li>{@code bit} → {@code Boolean}</li>
 * </ul>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlGraphQLSchemaGeneratorImplement implements IGraphQLSchemaGenerator {
    private static final Logger log = LoggerFactory.getLogger(MysqlGraphQLSchemaGeneratorImplement.class);

    // Sort-direction enum shared across all table filter inputs
    private static final GraphQLEnumType SORT_DIRECTION_ENUM = GraphQLEnumType.newEnum()
            .name("SortDirection")
            .value("ASC")
            .value("DESC")
            .build();

    @Override
    public GraphQLSchema generateSchema(Map<String, TableInfo> tables) {
        return generateSchema(tables, List.of(), List.of());
    }

    @Override
    public GraphQLSchema generateSchema(Map<String, TableInfo> tables,
                                        List<CustomEnumInfo> customEnums,
                                        List<CustomCompositeTypeInfo> customComposites) {
        Set<GraphQLType> additionalTypes = new HashSet<>();
        additionalTypes.add(SORT_DIRECTION_ENUM);
        additionalTypes.add(JsonScalar.JSON);

        Map<String, GraphQLObjectType> tableTypes = new HashMap<>();

        // ── Build object types with FK relationship fields using type references ─
        // GraphQLTypeReference allows forward/circular references — resolved at schema build time.
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();

            GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject().name(tableName);

            // Scalar columns
            for (ColumnInfo col : tableInfo.getColumns()) {
                GraphQLOutputType gqlType = mapType(col.getType());
                if (!col.isNullable() && col.isPrimaryKey()) {
                    gqlType = new GraphQLNonNull(gqlType);
                }
                typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(col.getName())
                        .type(gqlType)
                        .build());
            }

            // Forward FK fields (e.g. orders.customer → Customer)
            for (ForeignKeyInfo fk : tableInfo.getForeignKeys()) {
                if (tables.containsKey(fk.getReferencedTable())) {
                    typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                            .name(fk.getReferencedTable().toLowerCase())
                            .type(new GraphQLTypeReference(fk.getReferencedTable()))
                            .build());
                }
            }

            // Reverse FK fields (e.g. customer.orders → [orders])
            for (Map.Entry<String, TableInfo> otherEntry : tables.entrySet()) {
                String otherTableName = otherEntry.getKey();
                TableInfo otherTableInfo = otherEntry.getValue();
                if (otherTableName.equals(tableName) || otherTableInfo.isView()) continue;
                for (ForeignKeyInfo otherFk : otherTableInfo.getForeignKeys()) {
                    if (otherFk.getReferencedTable().equalsIgnoreCase(tableName)) {
                        String reverseFieldName = otherTableName.toLowerCase();
                        if (!reverseFieldName.endsWith("s")) reverseFieldName += "s";
                        typeBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                                .name(reverseFieldName)
                                .type(new GraphQLList(new GraphQLTypeReference(otherTableName)))
                                .build());
                    }
                }
            }

            tableTypes.put(tableName, typeBuilder.build());
        }

        // Build connection/edge/pageInfo types per table
        Map<String, GraphQLObjectType> edgeTypes = new HashMap<>();
        Map<String, GraphQLObjectType> connectionTypes = new HashMap<>();
        buildConnectionTypes(tableTypes, edgeTypes, connectionTypes, additionalTypes);

        // Add shared filter input types
        additionalTypes.add(STRING_FILTER_INPUT);
        additionalTypes.add(INT_FILTER_INPUT);
        additionalTypes.add(FLOAT_FILTER_INPUT);

        // ── Query type ────────────────────────────────────────────────────────
        GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject().name("Query");
        // ── Mutation type ────────────────────────────────────────────────────
        GraphQLObjectType.Builder mutationBuilder = GraphQLObjectType.newObject().name("Mutation");

        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();
            GraphQLObjectType tableType = tableTypes.get(tableName);

            GraphQLInputObjectType whereInput = buildWhereInput(tableName, tableInfo);
            GraphQLInputObjectType orderByInput = buildOrderByInput(tableName, tableInfo);
            additionalTypes.add(whereInput);
            additionalTypes.add(orderByInput);

            // ── Query fields ─────────────────────────────────────────────────
            queryBuilder.field(buildListQueryField(tableName, tableType, tableInfo, whereInput, orderByInput));
            queryBuilder.field(buildConnectionQueryField(tableName, connectionTypes.get(tableName)));
            queryBuilder.field(buildAggregateQueryField(tableName, tableInfo));

            // ── Mutation fields (skip views — read-only) ─────────────────────
            if (!tableInfo.isView()) {
                GraphQLInputObjectType createInput = buildCreateInput(tableName, tableInfo);
                GraphQLInputObjectType updateInput = buildUpdateInput(tableName, tableInfo);
                if (createInput != null) {
                    additionalTypes.add(createInput);
                    mutationBuilder.field(buildCreateMutationField(tableName, tableType, createInput));
                    mutationBuilder.field(buildBulkCreateMutationField(tableName, tableType, createInput));
                    mutationBuilder.field(buildCreateWithRelationsMutationField(tableName, tableType, createInput));
                }
                additionalTypes.add(updateInput);
                mutationBuilder.field(buildUpdateMutationField(tableName, tableType, updateInput));
                mutationBuilder.field(buildDeleteMutationField(tableName, tableType));
            }
        }

        additionalTypes.addAll(edgeTypes.values());
        additionalTypes.addAll(connectionTypes.values());
        additionalTypes.addAll(tableTypes.values());

        return GraphQLSchema.newSchema()
                .query(queryBuilder.build())
                .mutation(mutationBuilder.build())
                .additionalTypes(additionalTypes)
                .build();
    }

    // ─── Connection types ────────────────────────────────────────────────────

    private void buildConnectionTypes(Map<String, GraphQLObjectType> tableTypes,
                                      Map<String, GraphQLObjectType> edgeTypes,
                                      Map<String, GraphQLObjectType> connectionTypes,
                                      Set<GraphQLType> additionalTypes) {
        GraphQLObjectType pageInfoType = GraphQLObjectType.newObject()
                .name("PageInfo")
                .field(f -> f.name("hasNextPage").type(new GraphQLNonNull(GraphQLBoolean)))
                .field(f -> f.name("hasPreviousPage").type(new GraphQLNonNull(GraphQLBoolean)))
                .field(f -> f.name("startCursor").type(GraphQLString))
                .field(f -> f.name("endCursor").type(GraphQLString))
                .build();
        additionalTypes.add(pageInfoType);

        for (Map.Entry<String, GraphQLObjectType> e : tableTypes.entrySet()) {
            String tableName = e.getKey();
            GraphQLObjectType tableType = e.getValue();

            GraphQLObjectType edgeType = GraphQLObjectType.newObject()
                    .name(tableName + "Edge")
                    .field(f -> f.name("node").type(tableType))
                    .field(f -> f.name("cursor").type(new GraphQLNonNull(GraphQLString)))
                    .build();
            edgeTypes.put(tableName, edgeType);

            GraphQLObjectType connectionType = GraphQLObjectType.newObject()
                    .name(tableName + "Connection")
                    .field(f -> f.name("edges").type(new GraphQLList(edgeType)))
                    .field(f -> f.name("pageInfo").type(new GraphQLNonNull(pageInfoType)))
                    .field(f -> f.name("totalCount").type(GraphQLInt))
                    .build();
            connectionTypes.put(tableName, connectionType);
        }
    }

    // ─── Query field builders ────────────────────────────────────────────────

    /** Shared filter input types (one per scalar type). */
    private static final GraphQLInputObjectType STRING_FILTER_INPUT = GraphQLInputObjectType.newInputObject()
            .name("StringFilterInput")
            .field(f -> f.name("eq").type(GraphQLString))
            .field(f -> f.name("neq").type(GraphQLString))
            .field(f -> f.name("contains").type(GraphQLString))
            .field(f -> f.name("startsWith").type(GraphQLString))
            .field(f -> f.name("endsWith").type(GraphQLString))
            .field(f -> f.name("like").type(GraphQLString))
            .field(f -> f.name("isNull").type(GraphQLBoolean))
            .field(f -> f.name("isNotNull").type(GraphQLBoolean))
            .field(f -> f.name("in").type(new GraphQLList(GraphQLString)))
            .field(f -> f.name("notIn").type(new GraphQLList(GraphQLString)))
            .build();

    private static final GraphQLInputObjectType INT_FILTER_INPUT = GraphQLInputObjectType.newInputObject()
            .name("IntFilterInput")
            .field(f -> f.name("eq").type(GraphQLInt))
            .field(f -> f.name("neq").type(GraphQLInt))
            .field(f -> f.name("gt").type(GraphQLInt))
            .field(f -> f.name("gte").type(GraphQLInt))
            .field(f -> f.name("lt").type(GraphQLInt))
            .field(f -> f.name("lte").type(GraphQLInt))
            .field(f -> f.name("isNull").type(GraphQLBoolean))
            .field(f -> f.name("isNotNull").type(GraphQLBoolean))
            .field(f -> f.name("in").type(new GraphQLList(GraphQLInt)))
            .field(f -> f.name("notIn").type(new GraphQLList(GraphQLInt)))
            .build();

    private static final GraphQLInputObjectType FLOAT_FILTER_INPUT = GraphQLInputObjectType.newInputObject()
            .name("FloatFilterInput")
            .field(f -> f.name("eq").type(GraphQLFloat))
            .field(f -> f.name("neq").type(GraphQLFloat))
            .field(f -> f.name("gt").type(GraphQLFloat))
            .field(f -> f.name("gte").type(GraphQLFloat))
            .field(f -> f.name("lt").type(GraphQLFloat))
            .field(f -> f.name("lte").type(GraphQLFloat))
            .field(f -> f.name("isNull").type(GraphQLBoolean))
            .field(f -> f.name("isNotNull").type(GraphQLBoolean))
            .field(f -> f.name("in").type(new GraphQLList(GraphQLFloat)))
            .field(f -> f.name("notIn").type(new GraphQLList(GraphQLFloat)))
            .build();

    private GraphQLInputObjectType buildWhereInput(String tableName, TableInfo tableInfo) {
        GraphQLInputObjectType.Builder b = GraphQLInputObjectType.newInputObject()
                .name(capitalize(tableName) + "WhereInput");
        for (ColumnInfo col : tableInfo.getColumns()) {
            GraphQLInputType filterType = filterInputFor(col.getType());
            b.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col.getName())
                    .type(filterType)
                    .build());
        }
        return b.build();
    }

    private GraphQLInputObjectType buildOrderByInput(String tableName, TableInfo tableInfo) {
        GraphQLInputObjectType.Builder b = GraphQLInputObjectType.newInputObject()
                .name(capitalize(tableName) + "OrderByInput");
        for (ColumnInfo col : tableInfo.getColumns()) {
            b.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col.getName())
                    .type(GraphQLString)
                    .build());
        }
        return b.build();
    }

    private GraphQLInputType filterInputFor(String dbType) {
        if (dbType == null) return STRING_FILTER_INPUT;
        String t = dbType.toLowerCase().trim();
        return switch (t) {
            case "int", "integer", "bigint", "smallint", "mediumint", "tinyint" -> INT_FILTER_INPUT;
            case "decimal", "numeric", "float", "double", "real" -> FLOAT_FILTER_INPUT;
            default -> STRING_FILTER_INPUT;
        };
    }

    private GraphQLFieldDefinition buildListQueryField(String tableName,
                                                        GraphQLObjectType tableType,
                                                        TableInfo tableInfo,
                                                        GraphQLInputObjectType whereInput,
                                                        GraphQLInputObjectType orderByInput) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName)
                .type(new GraphQLList(tableType))
                .argument(GraphQLArgument.newArgument().name("limit").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("offset").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("orderBy").type(orderByInput).build())
                .argument(GraphQLArgument.newArgument().name("where").type(whereInput).build())
                .build();
    }

    private GraphQLFieldDefinition buildConnectionQueryField(String tableName,
                                                              GraphQLObjectType connectionType) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName + "Connection")
                .type(connectionType)
                .argument(GraphQLArgument.newArgument().name("first").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("after").type(GraphQLString).build())
                .argument(GraphQLArgument.newArgument().name("last").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("before").type(GraphQLString).build())
                .build();
    }

    private GraphQLFieldDefinition buildAggregateQueryField(String tableName, TableInfo tableInfo) {
        GraphQLObjectType aggregateType = GraphQLObjectType.newObject()
                .name(tableName + "Aggregate")
                .field(f -> f.name("count").type(GraphQLInt))
                .field(f -> f.name("sum").type(GraphQLFloat))
                .field(f -> f.name("avg").type(GraphQLFloat))
                .field(f -> f.name("min").type(GraphQLFloat))
                .field(f -> f.name("max").type(GraphQLFloat))
                .build();

        return GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName.toLowerCase() + "_aggregate")
                .type(aggregateType)
                .argument(GraphQLArgument.newArgument().name("where").type(GraphQLString).build())
                .build();
    }

    // ─── Mutation field builders ─────────────────────────────────────────────

    private GraphQLInputObjectType buildCreateInput(String tableName, TableInfo tableInfo) {
        GraphQLInputObjectType.Builder b = GraphQLInputObjectType.newInputObject()
                .name("Create" + capitalize(tableName) + "Input");
        boolean hasFields = false;
        for (ColumnInfo col : tableInfo.getColumns()) {
            if (col.isPrimaryKey()) continue; // skip auto-increment PK on create
            b.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col.getName())
                    .type((GraphQLInputType) mapType(col.getType()))
                    .build());
            hasFields = true;
        }
        return hasFields ? b.build() : null;
    }

    private GraphQLInputObjectType buildUpdateInput(String tableName, TableInfo tableInfo) {
        GraphQLInputObjectType.Builder b = GraphQLInputObjectType.newInputObject()
                .name("Update" + capitalize(tableName) + "Input");
        for (ColumnInfo col : tableInfo.getColumns()) {
            if (col.isPrimaryKey()) continue;
            b.field(GraphQLInputObjectField.newInputObjectField()
                    .name(col.getName())
                    .type((GraphQLInputType) mapType(col.getType()))
                    .build());
        }
        return b.build();
    }

    private GraphQLFieldDefinition buildCreateMutationField(String tableName,
                                                             GraphQLObjectType tableType,
                                                             GraphQLInputObjectType createInput) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("create" + capitalize(tableName))
                .type(tableType)
                .argument(GraphQLArgument.newArgument().name("input").type(createInput).build())
                .build();
    }

    private GraphQLFieldDefinition buildBulkCreateMutationField(String tableName,
                                                                  GraphQLObjectType tableType,
                                                                  GraphQLInputObjectType createInput) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("createMany" + capitalize(tableName) + "s")
                .type(new GraphQLList(tableType))
                .argument(GraphQLArgument.newArgument().name("input")
                        .type(new GraphQLList(createInput)).build())
                .build();
    }

    private GraphQLFieldDefinition buildCreateWithRelationsMutationField(String tableName,
                                                                          GraphQLObjectType tableType,
                                                                          GraphQLInputObjectType createInput) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("create" + capitalize(tableName) + "WithRelations")
                .type(tableType)
                .argument(GraphQLArgument.newArgument().name("input").type(createInput).build())
                .build();
    }

    private GraphQLFieldDefinition buildUpdateMutationField(String tableName,
                                                             GraphQLObjectType tableType,
                                                             GraphQLInputObjectType updateInput) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("update" + capitalize(tableName))
                .type(tableType)
                .argument(GraphQLArgument.newArgument().name("id").type(GraphQLInt).build())
                .argument(GraphQLArgument.newArgument().name("input").type(updateInput).build())
                .build();
    }

    private GraphQLFieldDefinition buildDeleteMutationField(String tableName,
                                                             GraphQLObjectType tableType) {
        return GraphQLFieldDefinition.newFieldDefinition()
                .name("delete" + capitalize(tableName))
                .type(tableType)
                .argument(GraphQLArgument.newArgument().name("id").type(GraphQLInt).build())
                .build();
    }

    // ─── Type mapping ────────────────────────────────────────────────────────

    GraphQLOutputType mapType(String dbType) {
        if (dbType == null) return GraphQLString;
        String t = dbType.toLowerCase().trim();

        return switch (t) {
            case "int", "integer", "bigint", "smallint", "mediumint", "tinyint" -> GraphQLInt;
            case "decimal", "numeric", "float", "double", "real" -> GraphQLFloat;
            case "json" -> JsonScalar.JSON;
            case "bit" -> GraphQLBoolean;
            // dates, times, enums, and all text types map to String
            default -> GraphQLString;
        };
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
