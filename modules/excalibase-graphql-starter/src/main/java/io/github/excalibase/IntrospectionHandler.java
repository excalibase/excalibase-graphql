package io.github.excalibase;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.*;

import java.util.*;

import static graphql.Scalars.*;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLEnumType.newEnum;

/**
 * Builds a GraphQL-Java schema from SchemaInfo metadata for introspection queries only.
 * No resolvers — only used for __schema / __type queries.
 */
public class IntrospectionHandler {

    private final GraphQL graphQL;

    public IntrospectionHandler(SchemaInfo schemaInfo) {
        this.graphQL = GraphQL.newGraphQL(buildSchema(schemaInfo)).build();
    }

    public Map<String, Object> execute(String query, Map<String, Object> variables) {
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables != null ? variables : Map.of())
                .build();
        ExecutionResult result = graphQL.execute(input);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", result.getData());
        if (!result.getErrors().isEmpty()) {
            response.put("errors", result.getErrors());
        }
        return response;
    }

    private GraphQLSchema buildSchema(SchemaInfo schemaInfo) {
        Map<String, GraphQLObjectType> types = new LinkedHashMap<>();
        Map<String, GraphQLInputObjectType> whereTypes = new LinkedHashMap<>();
        Map<String, GraphQLInputObjectType> createInputs = new LinkedHashMap<>();

        // Build enum types from schema metadata
        Map<String, GraphQLEnumType> enumTypeMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : schemaInfo.getEnumTypes().entrySet()) {
            String enumName = NamingUtils.capitalize(entry.getKey());
            GraphQLEnumType.Builder enumBuilder = newEnum().name(enumName);
            for (String label : entry.getValue()) {
                enumBuilder.value(label);
            }
            enumTypeMap.put(entry.getKey(), enumBuilder.build());
        }

        // String filter input
        GraphQLInputObjectType stringFilter = GraphQLInputObjectType.newInputObject()
                .name("StringFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name("eq").type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("neq").type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("like").type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("ilike").type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("in").type(GraphQLList.list(GraphQLString)).build())
                .build();

        GraphQLInputObjectType intFilter = GraphQLInputObjectType.newInputObject()
                .name("IntFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name("eq").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("neq").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("gt").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("gte").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("lt").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("lte").type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name("in").type(GraphQLList.list(GraphQLInt)).build())
                .build();

        // Build types for each table
        for (String table : schemaInfo.getTableNames()) {
            Set<String> columns = schemaInfo.getColumns(table);
            String typeName = NamingUtils.capitalize(table);

            // Object type
            GraphQLObjectType.Builder typeBuilder = newObject().name(typeName);
            for (String col : columns) {
                String enumTypeName = schemaInfo.getEnumType(table, col);
                GraphQLOutputType colType = enumTypeName != null && enumTypeMap.containsKey(enumTypeName)
                        ? enumTypeMap.get(enumTypeName)
                        : mapColumnType(schemaInfo.getColumnType(table, col));
                typeBuilder.field(newFieldDefinition()
                        .name(col)
                        .type(colType)
                        .build());
            }
            types.put(table, typeBuilder.build());

            // Where input type
            GraphQLInputObjectType.Builder whereBuilder = GraphQLInputObjectType.newInputObject()
                    .name(typeName + "WhereInput");
            for (String col : columns) {
                String colType = schemaInfo.getColumnType(table, col);
                GraphQLInputObjectType filter = isNumericType(colType) ? intFilter : stringFilter;
                whereBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(col).type(filter).build());
            }
            whereTypes.put(table, whereBuilder.build());

            // Create input type
            GraphQLInputObjectType.Builder createBuilder = GraphQLInputObjectType.newInputObject()
                    .name(typeName + "CreateInput");
            for (String col : columns) {
                String enumTypeName = schemaInfo.getEnumType(table, col);
                GraphQLInputType inputType = enumTypeName != null && enumTypeMap.containsKey(enumTypeName)
                        ? enumTypeMap.get(enumTypeName)
                        : mapInputType(schemaInfo.getColumnType(table, col));
                createBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(col).type(inputType).build());
            }
            createInputs.put(table, createBuilder.build());
        }

        // Shared PageInfo type
        GraphQLObjectType pageInfoType = newObject().name("PageInfo")
                .field(newFieldDefinition().name("hasNextPage").type(GraphQLBoolean).build())
                .field(newFieldDefinition().name("hasPreviousPage").type(GraphQLBoolean).build())
                .field(newFieldDefinition().name("startCursor").type(GraphQLString).build())
                .field(newFieldDefinition().name("endCursor").type(GraphQLString).build())
                .build();

        // Build query type
        GraphQLObjectType.Builder queryBuilder = newObject().name("Query");
        for (String table : schemaInfo.getTableNames()) {
            String fieldName = NamingUtils.toLowerCamelCase(table);
            String typeName = NamingUtils.capitalize(table);
            GraphQLObjectType type = types.get(table);

            // List query
            queryBuilder.field(newFieldDefinition()
                    .name(fieldName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name("where").type(whereTypes.get(table)).build())
                    .argument(GraphQLArgument.newArgument().name("limit").type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name("offset").type(GraphQLInt).build())
                    .build());

            // Connection query
            GraphQLObjectType edgeType = newObject().name(typeName + "Edge")
                    .field(newFieldDefinition().name("node").type(type).build())
                    .field(newFieldDefinition().name("cursor").type(GraphQLString).build())
                    .build();
            GraphQLObjectType connectionType = newObject().name(typeName + "Connection")
                    .field(newFieldDefinition().name("edges").type(GraphQLList.list(edgeType)).build())
                    .field(newFieldDefinition().name("pageInfo").type(pageInfoType).build())
                    .field(newFieldDefinition().name("totalCount").type(GraphQLInt).build())
                    .build();
            queryBuilder.field(newFieldDefinition()
                    .name(fieldName + "Connection")
                    .type(connectionType)
                    .argument(GraphQLArgument.newArgument().name("first").type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name("after").type(GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name("last").type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name("before").type(GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name("where").type(whereTypes.get(table)).build())
                    .build());

            // Aggregate query
            queryBuilder.field(newFieldDefinition()
                    .name(fieldName + "Aggregate")
                    .type(newObject().name(typeName + "Aggregate")
                            .field(newFieldDefinition().name("count").type(GraphQLInt).build())
                            .build())
                    .build());
        }

        // Build mutation type
        GraphQLObjectType.Builder mutationBuilder = newObject().name("Mutation");
        for (String table : schemaInfo.getTableNames()) {
            // Skip views — they are read-only
            if (schemaInfo.isView(table)) continue;

            String typeName = NamingUtils.capitalize(table);
            GraphQLObjectType type = types.get(table);
            GraphQLInputObjectType createInput = createInputs.get(table);

            mutationBuilder.field(newFieldDefinition()
                    .name("create" + typeName)
                    .type(type)
                    .argument(GraphQLArgument.newArgument().name("input").type(createInput).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name("createMany" + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name("inputs").type(GraphQLList.list(createInput)).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name("update" + typeName)
                    .type(type)
                    .argument(GraphQLArgument.newArgument().name("input").type(createInput).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name("delete" + typeName)
                    .type(type)
                    .argument(GraphQLArgument.newArgument().name("input").type(createInput).build())
                    .build());
        }

        // Add stored procedure mutations
        for (var procEntry : schemaInfo.getStoredProcedures().entrySet()) {
            String procName = procEntry.getKey();
            SchemaInfo.ProcedureInfo proc = procEntry.getValue();
            String mutationName = "call" + NamingUtils.capitalize(procName);

            GraphQLFieldDefinition.Builder procField = newFieldDefinition()
                    .name(mutationName)
                    .type(GraphQLString); // Returns JSON string with OUT params

            for (SchemaInfo.ProcParam param : proc.inParams()) {
                GraphQLInputType argType = mapInputType(param.type());
                procField.argument(GraphQLArgument.newArgument()
                        .name(param.name())
                        .type(argType)
                        .build());
            }
            mutationBuilder.field(procField.build());
        }

        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema()
                .query(queryBuilder.build())
                .mutation(mutationBuilder.build());
        // Register enum types as additional types so they're discoverable via __type
        for (GraphQLEnumType enumType : enumTypeMap.values()) {
            schemaBuilder.additionalType(enumType);
        }
        // Register composite types as GraphQL object types
        for (var entry : schemaInfo.getCompositeTypes().entrySet()) {
            String typeName = NamingUtils.capitalize(entry.getKey());
            GraphQLObjectType.Builder ctBuilder = newObject().name(typeName);
            for (SchemaInfo.CompositeTypeField field : entry.getValue()) {
                ctBuilder.field(newFieldDefinition()
                        .name(field.name())
                        .type(mapColumnType(field.type()))
                        .build());
            }
            schemaBuilder.additionalType(ctBuilder.build());
        }
        return schemaBuilder.build();
    }

    private GraphQLInputType mapInputType(String dbType) {
        if (dbType == null) return GraphQLString;
        String t = dbType.toLowerCase();
        if (t.contains("int")) return GraphQLInt;
        if (t.contains("float") || t.contains("double") || t.contains("numeric") || t.contains("decimal") || t.contains("real")) return GraphQLFloat;
        if (t.contains("bool")) return GraphQLBoolean;
        return GraphQLString;
    }

    private GraphQLOutputType mapColumnType(String dbType) {
        if (dbType == null) return GraphQLString;
        String t = dbType.toLowerCase();
        if (t.contains("int")) return GraphQLInt;
        if (t.contains("float") || t.contains("double") || t.contains("numeric") || t.contains("decimal") || t.contains("real")) return GraphQLFloat;
        if (t.contains("bool")) return GraphQLBoolean;
        return GraphQLString;
    }

    private boolean isNumericType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        return t.contains("int") || t.contains("float") || t.contains("double") || t.contains("numeric") || t.contains("decimal") || t.contains("real");
    }

}
