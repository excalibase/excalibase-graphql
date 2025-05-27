package io.github.excalibase.schema.generator.postgres;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeReference;
import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.exception.EmptySchemaException;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;

import java.util.Map;

import static graphql.Scalars.*;

@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresGraphQLSchemaGeneratorImplement implements IGraphQLSchemaGenerator {
    @Override
    public GraphQLSchema generateSchema(Map<String, TableInfo> tables) {
        if (tables == null || tables.isEmpty()) {
            throw new EmptySchemaException("Cannot generate schema with empty postgres schema");
        }
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema();

        // Create common types
        GraphQLEnumType orderDirectionEnum = createOrderDirectionEnum();
        GraphQLObjectType pageInfoType = createPageInfoType();
        
        schemaBuilder.additionalType(orderDirectionEnum);
        schemaBuilder.additionalType(pageInfoType);

        // Create type definitions for each table
        createTableTypes(schemaBuilder, tables, pageInfoType);

        // Create Query type
        GraphQLObjectType queryType = createQueryType(tables);
        schemaBuilder.query(queryType);

        // Create Mutation type
        GraphQLObjectType mutationType = createMutationType(tables);
        schemaBuilder.mutation(mutationType);

        return schemaBuilder.build();
    }

    private GraphQLEnumType createOrderDirectionEnum() {
        return GraphQLEnumType.newEnum()
                .name("OrderDirection")
                .description("Possible directions in which to order a list of items")
                .value("ASC", "ASC", "Sort in ascending order")
                .value("DESC", "DESC", "Sort in descending order")
                .build();
    }

    private GraphQLObjectType createPageInfoType() {
        return GraphQLObjectType.newObject()
                .name("PageInfo")
                .description("Information about pagination in a connection")
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("hasNextPage")
                        .type(GraphQLBoolean)
                        .description("When paginating forwards, are there more items?")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("hasPreviousPage")
                        .type(GraphQLBoolean)
                        .description("When paginating backwards, are there more items?")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("startCursor")
                        .type(GraphQLString)
                        .description("The cursor to continue from when paginating backwards")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("endCursor")
                        .type(GraphQLString)
                        .description("The cursor to continue from when paginating forwards")
                        .build())
                .build();
    }

    private void createTableTypes(GraphQLSchema.Builder schemaBuilder, Map<String, TableInfo> tables, GraphQLObjectType pageInfoType) {
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();

            // Create ObjectType for the table
            GraphQLObjectType nodeType = createNodeType(tableName, tableInfo);
            schemaBuilder.additionalType(nodeType);

            // Create Edge type for this node
            GraphQLObjectType edgeType = createEdgeType(tableName, nodeType);
            schemaBuilder.additionalType(edgeType);

            // Create Connection type for this edge
            GraphQLObjectType connectionType = createConnectionType(tableName, edgeType, pageInfoType);
            schemaBuilder.additionalType(connectionType);

            // Create order by input type
            GraphQLInputObjectType orderByInputType = createOrderByInput(tableName, tableInfo);
            schemaBuilder.additionalType(orderByInputType);
        }
    }

    private GraphQLObjectType createNodeType(String tableName, TableInfo tableInfo) {
        GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject()
                .name(tableName)
                .description("Type for table " + tableName);

        // Add fields for each column
        for (ColumnInfo column : tableInfo.getColumns()) {
            GraphQLOutputType fieldType = mapDatabaseTypeToGraphQLType(column.getType());
            GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(column.getName())
                    .type(column.isNullable() ? fieldType : new GraphQLNonNull(fieldType))
                    .description("Column " + column.getName());

            typeBuilder.field(fieldBuilder.build());
        }

        // Add fields for relationships
        for (ForeignKeyInfo fk : tableInfo.getForeignKeys()) {
            GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(fk.getReferencedTable().toLowerCase())
                    .type(GraphQLTypeReference.typeRef(fk.getReferencedTable()))
                    .description("Relationship to " + fk.getReferencedTable());

            typeBuilder.field(fieldBuilder.build());
        }

        return typeBuilder.build();
    }

    private GraphQLObjectType createEdgeType(String tableName, GraphQLObjectType nodeType) {
        return GraphQLObjectType.newObject()
                .name(tableName + "Edge")
                .description("An edge in a connection for " + tableName)
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("node")
                        .type(nodeType)
                        .description("The item at the end of the edge")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("cursor")
                        .type(GraphQLString)
                        .description("A cursor for pagination")
                        .build())
                .build();
    }

    private GraphQLObjectType createConnectionType(String tableName, GraphQLObjectType edgeType, GraphQLObjectType pageInfoType) {
        return GraphQLObjectType.newObject()
                .name(tableName + "Connection")
                .description("A connection to a list of " + tableName)
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("edges")
                        .type(new GraphQLList(edgeType))
                        .description("A list of edges")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("pageInfo")
                        .type(new GraphQLNonNull(pageInfoType))
                        .description("Information to aid in pagination")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name("totalCount")
                        .type(GraphQLInt)
                        .description("The total number of items in the connection")
                        .build())
                .build();
    }

    private GraphQLInputObjectType createOrderByInput(String tableName, TableInfo tableInfo) {
        GraphQLInputObjectType.Builder orderByInputBuilder = GraphQLInputObjectType.newInputObject()
                .name(tableName + "OrderByInput")
                .description("Input for ordering " + tableName + " records");

        // Add each column as a potential sort field with direction
        for (ColumnInfo column : tableInfo.getColumns()) {
            orderByInputBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(column.getName())
                    .type(GraphQLTypeReference.typeRef("OrderDirection"))
                    .description("Order by " + column.getName())
                    .build());
        }

        return orderByInputBuilder.build();
    }

    private GraphQLObjectType createQueryType(Map<String, TableInfo> tables) {
        GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject()
                .name("Query")
                .description("Root query type");

        // Add query fields for each table
        for (String tableName : tables.keySet()) {
            TableInfo tableInfo = tables.get(tableName);
            addQueryFields(queryBuilder, tableName, tableInfo);
            addConnectionFields(queryBuilder, tableName, tableInfo);
        }

        return queryBuilder.build();
    }

    private void addQueryFields(GraphQLObjectType.Builder queryBuilder, String tableName, TableInfo tableInfo) {
        GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName.toLowerCase())
                .type(new GraphQLList(GraphQLTypeReference.typeRef(tableName)))
                .description("Query all records from " + tableName);

        // Add filtering arguments
        for (ColumnInfo column : tableInfo.getColumns()) {
            addFilteringArguments(fieldBuilder, column);
        }

        // Add pagination arguments
        addPaginationArguments(fieldBuilder);

        // Add orderBy argument
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("orderBy")
                .type(GraphQLTypeReference.typeRef(tableName + "OrderByInput"))
                .description("Order results by specified fields")
                .build());

        queryBuilder.field(fieldBuilder.build());
    }

    private void addConnectionFields(GraphQLObjectType.Builder queryBuilder, String tableName, TableInfo tableInfo) {
        GraphQLFieldDefinition.Builder connectionFieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName.toLowerCase() + "Connection")
                .type(GraphQLTypeReference.typeRef(tableName + "Connection"))
                .description("Connection to " + tableName + " records for cursor-based pagination following the Relay specification");

        // Add filtering arguments
        for (ColumnInfo column : tableInfo.getColumns()) {
            addFilteringArguments(connectionFieldBuilder, column);
        }

        // Add ordering to connection fields
        connectionFieldBuilder.argument(GraphQLArgument.newArgument()
                .name("orderBy")
                .type(GraphQLTypeReference.typeRef(tableName + "OrderByInput"))
                .description("Order results by specified fields")
                .build());

        // Add cursor-based pagination arguments
        addCursorPaginationArguments(connectionFieldBuilder);

        queryBuilder.field(connectionFieldBuilder.build());
    }

    private void addFilteringArguments(GraphQLFieldDefinition.Builder fieldBuilder, ColumnInfo column) {
        GraphQLInputType argType = mapDatabaseTypeToGraphQLInputType(column.getType());

        // Add an argument for exact matching
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(column.getName())
                .type(argType)
                .description("Filter by " + column.getName())
                .build());

        // For string columns, add additional filtering options
        if (argType == GraphQLString) {
            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_contains")
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " contains text")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_startsWith")
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " starts with text")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_endsWith")
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " ends with text")
                    .build());
        }

        // For numeric columns, add comparison operators
        if (argType == GraphQLInt || argType == GraphQLFloat) {
            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_gt")
                    .type(argType)
                    .description("Filter where " + column.getName() + " is greater than")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_gte")
                    .type(argType)
                    .description("Filter where " + column.getName() + " is greater than or equal")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_lt")
                    .type(argType)
                    .description("Filter where " + column.getName() + " is less than")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_lte")
                    .type(argType)
                    .description("Filter where " + column.getName() + " is less than or equal")
                    .build());
        }
    }

    private void addPaginationArguments(GraphQLFieldDefinition.Builder fieldBuilder) {
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("limit")
                .type(GraphQLInt)
                .description("Maximum number of records to return")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("offset")
                .type(GraphQLInt)
                .description("Number of records to skip")
                .build());
    }

    private void addCursorPaginationArguments(GraphQLFieldDefinition.Builder fieldBuilder) {
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("first")
                .type(GraphQLInt)
                .description("Returns the first n elements from the list")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("after")
                .type(GraphQLString)
                .description("Returns elements after the provided cursor")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("last")
                .type(GraphQLInt)
                .description("Returns the last n elements from the list")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name("before")
                .type(GraphQLString)
                .description("Returns elements before the provided cursor")
                .build());
    }

    private GraphQLObjectType createMutationType(Map<String, TableInfo> tables) {
        GraphQLObjectType.Builder mutationBuilder = GraphQLObjectType.newObject()
                .name("Mutation")
                .description("Root mutation type");

        // Add mutation fields for each table
        for (String tableName : tables.keySet()) {
            TableInfo tableInfo = tables.get(tableName);
            addMutationFields(mutationBuilder, tableName, tableInfo, tables);
        }

        return mutationBuilder.build();
    }

    private void addMutationFields(GraphQLObjectType.Builder mutationBuilder, String tableName, TableInfo tableInfo, Map<String, TableInfo> tables) {
        GraphQLInputObjectType createInputType = createInputTypes(tableName, tableInfo, false);
        GraphQLInputObjectType updateInputType = createInputTypes(tableName, tableInfo, true);

        // 1. Create mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("create" + capitalize(tableName))
                .description("Create a new " + tableName + " record")
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name("input")
                        .type(new GraphQLNonNull(createInputType))
                        .build())
                .build());

        // 2. Update mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("update" + capitalize(tableName))
                .description("Update an existing " + tableName + " record")
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name("input")
                        .type(new GraphQLNonNull(updateInputType))
                        .build())
                .build());

        // 3. Delete mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("delete" + capitalize(tableName))
                .description("Delete a " + tableName + " record")
                .type(GraphQLBoolean)
                .argument(GraphQLArgument.newArgument()
                        .name("id")
                        .type(new GraphQLNonNull(GraphQLID))
                        .description("Primary key of record to delete")
                        .build())
                .build());

        // 4. Bulk create mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("createMany" + capitalize(tableName) + "s")
                .description("Create multiple " + tableName + " records in a single operation")
                .type(new GraphQLList(GraphQLTypeReference.typeRef(tableName)))
                .argument(GraphQLArgument.newArgument()
                        .name("inputs")
                        .type(new GraphQLNonNull(new GraphQLList(new GraphQLNonNull(createInputType))))
                        .description("Array of " + tableName + " records to create")
                        .build())
                .build());

        // 5. Create with relationships mutation
        GraphQLInputObjectType relationshipInputType = createRelationshipInputType(tableName, tableInfo, tables);
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name("create" + capitalize(tableName) + "WithRelations")
                .description("Create a " + tableName + " record with related records")
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name("input")
                        .type(new GraphQLNonNull(relationshipInputType))
                        .description("Input data with relationship fields")
                        .build())
                .build());
    }

    private GraphQLInputObjectType createInputTypes(String tableName, TableInfo tableInfo, boolean isUpdate) {
        String typeName = tableName + (isUpdate ? "UpdateInput" : "CreateInput");
        String description = "Input for " + (isUpdate ? "updating" : "creating new") + " " + tableName + " records";

        GraphQLInputObjectType.Builder inputTypeBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName)
                .description(description);

        // Add fields with different rules for primary keys
        for (ColumnInfo column : tableInfo.getColumns()) {
            GraphQLInputType inputType = mapDatabaseTypeToGraphQLInputType(column.getType());

            if (isUpdate) {
                // For UPDATE input type, primary keys are required, others are optional
                inputTypeBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(column.getName())
                        .type(column.isPrimaryKey() ? new GraphQLNonNull(inputType) : inputType)
                        .description("Input for " + column.getName())
                        .build());
            } else {
                // For CREATE input type, all fields are optional
                inputTypeBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(column.getName())
                        .type(inputType)
                        .description("Input for " + column.getName())
                        .build());
            }
        }

        return inputTypeBuilder.build();
    }

    private GraphQLInputObjectType createRelationshipInputType(String tableName, TableInfo tableInfo, Map<String, TableInfo> tables) {
        GraphQLInputObjectType.Builder relationshipInputBuilder = GraphQLInputObjectType.newInputObject()
                .name(tableName + "RelationshipInput")
                .description("Input for creating " + tableName + " with relationships");

        // Add all fields from the regular create input
        for (ColumnInfo column : tableInfo.getColumns()) {
            GraphQLInputType inputType = mapDatabaseTypeToGraphQLInputType(column.getType());
            relationshipInputBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(column.getName())
                    .type(inputType)
                    .description("Input for " + column.getName())
                    .build());
        }

        // Add relationship fields
        addRelationshipFields(relationshipInputBuilder, tableName, tableInfo, tables);

        return relationshipInputBuilder.build();
    }

    private void addRelationshipFields(GraphQLInputObjectType.Builder relationshipInputBuilder, String tableName, TableInfo tableInfo, Map<String, TableInfo> tables) {
        // Add foreign key relationships
        for (ForeignKeyInfo fk : tableInfo.getForeignKeys()) {
            String referencedTable = fk.getReferencedTable();

            // Connect to existing record
            GraphQLInputObjectType connectInputType = GraphQLInputObjectType.newInputObject()
                    .name(tableName + capitalize(referencedTable) + "ConnectInput")
                    .description("Input to connect to existing " + referencedTable)
                    .field(GraphQLInputObjectField.newInputObjectField()
                            .name("id")
                            .type(new GraphQLNonNull(GraphQLID))
                            .description("ID of existing " + referencedTable + " to connect")
                            .build())
                    .build();

            relationshipInputBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(referencedTable.toLowerCase() + "_connect")
                    .type(connectInputType)
                    .description("Connect to existing " + referencedTable)
                    .build());

            // Create and connect
            relationshipInputBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(referencedTable.toLowerCase() + "_create")
                    .type(GraphQLTypeReference.typeRef(referencedTable + "CreateInput"))
                    .description("Create new " + referencedTable + " and connect")
                    .build());
        }

        // Check for reverse relationships (other tables referencing this one)
        for (String otherTableName : tables.keySet()) {
            if (otherTableName.equals(tableName)) continue;

            TableInfo otherTableInfo = tables.get(otherTableName);
            for (ForeignKeyInfo fk : otherTableInfo.getForeignKeys()) {
                if (fk.getReferencedTable().equals(tableName)) {
                    // This is a reverse relationship - the other table references this one
                    relationshipInputBuilder.field(GraphQLInputObjectField.newInputObjectField()
                            .name(otherTableName.toLowerCase() + "_createMany")
                            .type(new GraphQLList(GraphQLTypeReference.typeRef(otherTableName + "CreateInput")))
                            .description("Create multiple " + otherTableName + " records related to this " + tableName)
                            .build());
                }
            }
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private GraphQLOutputType mapDatabaseTypeToGraphQLType(String dbType) {
        String type = dbType.toLowerCase();

        if (type.contains("int") || type.equals("bigint") || type.equals("smallint")) {
            return GraphQLInt;
        } else if (type.contains("numeric") || type.contains("decimal") ||
                type.contains("real") || type.contains("double precision")) {
            return GraphQLFloat;
        } else if (type.contains("boolean")) {
            return GraphQLBoolean;
        } else if (type.contains("uuid")) {
            return GraphQLID;
        } else if (type.contains("timestamp") || type.contains("date") ||
                type.contains("time")) {
            return GraphQLString;
        } else {
            return GraphQLString;
        }
    }

    private GraphQLInputType mapDatabaseTypeToGraphQLInputType(String dbType) {
        return (GraphQLInputType) mapDatabaseTypeToGraphQLType(dbType);
    }
}
