package io.github.excalibase.schema;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.scalars.ExtendedScalars;
import graphql.schema.*;

import java.util.*;

import static graphql.Scalars.*;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLEnumType.newEnum;
import static io.github.excalibase.schema.GraphqlConstants.*;

/**
 * Builds a GraphQL-Java schema from SchemaInfo metadata for introspection queries only.
 * No resolvers — only used for __schema / __type queries.
 */
public class IntrospectionHandler {

    private final GraphQL graphQL;

    public IntrospectionHandler(SchemaInfo schemaInfo) {
        this.graphQL = GraphQL.newGraphQL(buildSchema(schemaInfo)).build();
    }

    /** Derive the GraphQL type name from a table key. Compound keys always prefix. */
    private static String typeName(String tableKey) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            String rawTable = tableKey.substring(tableKey.indexOf('.') + 1);
            return NamingUtils.schemaTypeName(schema, rawTable);
        }
        return NamingUtils.capitalize(tableKey);
    }

    /** Derive the GraphQL field name from a table key. Compound keys always prefix. */
    private static String fieldName(String tableKey) {
        if (tableKey.contains(".")) {
            String schema = tableKey.substring(0, tableKey.indexOf('.'));
            String rawTable = tableKey.substring(tableKey.indexOf('.') + 1);
            return NamingUtils.schemaFieldName(schema, rawTable);
        }
        return NamingUtils.toLowerCamelCase(tableKey);
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
            String enumName = typeName(entry.getKey());
            GraphQLEnumType.Builder enumBuilder = newEnum().name(enumName);
            if (entry.getKey().contains(".")) {
                String schema = entry.getKey().substring(0, entry.getKey().indexOf('.'));
                String rawEnum = entry.getKey().substring(entry.getKey().indexOf('.') + 1);
                enumBuilder.description("Enum " + rawEnum + " from schema " + schema);
            }
            for (String label : entry.getValue()) {
                enumBuilder.value(label);
            }
            enumTypeMap.put(entry.getKey(), enumBuilder.build());
        }

        // String filter input
        GraphQLInputObjectType stringFilter = GraphQLInputObjectType.newInputObject()
                .name("StringFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_ILIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLString)).build())
                .build();

        GraphQLInputObjectType intFilter = GraphQLInputObjectType.newInputObject()
                .name("IntFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLInt).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLInt)).build())
                .build();

        // Build types for each table
        for (String table : schemaInfo.getTableNames()) {
            Set<String> columns = schemaInfo.getColumns(table);
            String typeName = typeName(table);

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
            // Add computed fields to the type
            List<SchemaInfo.ComputedField> computed = schemaInfo.getComputedFields(table);
            if (computed != null) {
                String rawTable = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
                for (SchemaInfo.ComputedField cf : computed) {
                    // Field name: strip table prefix if present (e.g., "customer_full_name" → "full_name")
                    String cfName = cf.functionName();
                    if (cfName.startsWith(rawTable + "_")) {
                        cfName = cfName.substring(rawTable.length() + 1);
                    }
                    typeBuilder.field(newFieldDefinition()
                            .name(cfName)
                            .type(mapColumnType(cf.returnType()))
                            .build());
                }
            }
            types.put(table, typeBuilder.build());

            // Where input type
            GraphQLInputObjectType.Builder whereBuilder = GraphQLInputObjectType.newInputObject()
                    .name(typeName + WHERE_INPUT_SUFFIX);
            for (String col : columns) {
                String colType = schemaInfo.getColumnType(table, col);
                GraphQLInputObjectType filter = isNumericType(colType) ? intFilter : stringFilter;
                whereBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(col).type(filter).build());
            }
            whereTypes.put(table, whereBuilder.build());

            // Create input type
            GraphQLInputObjectType.Builder createBuilder = GraphQLInputObjectType.newInputObject()
                    .name(typeName + CREATE_INPUT_SUFFIX);
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
        GraphQLObjectType pageInfoType = newObject().name(TYPE_PAGE_INFO)
                .field(newFieldDefinition().name(FIELD_HAS_NEXT_PAGE).type(GraphQLBoolean).build())
                .field(newFieldDefinition().name(FIELD_HAS_PREVIOUS_PAGE).type(GraphQLBoolean).build())
                .field(newFieldDefinition().name(FIELD_START_CURSOR).type(GraphQLString).build())
                .field(newFieldDefinition().name(FIELD_END_CURSOR).type(GraphQLString).build())
                .build();

        // Build query type
        GraphQLObjectType.Builder queryBuilder = newObject().name(TYPE_QUERY);

        // GraphQL requires at least one field in Query — add placeholder when no tables exist
        if (schemaInfo.getTableNames().isEmpty()) {
            queryBuilder.field(newFieldDefinition().name(EMPTY_FIELD).type(GraphQLString).build());
        }

        for (String table : schemaInfo.getTableNames()) {
            String fName = fieldName(table);
            String tName = typeName(table);
            GraphQLObjectType type = types.get(table);

            // List query
            queryBuilder.field(newFieldDefinition()
                    .name(fName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereTypes.get(table)).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_LIMIT).type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_OFFSET).type(GraphQLInt).build())
                    .build());

            // Connection query
            GraphQLObjectType edgeType = newObject().name(tName + EDGE_SUFFIX)
                    .field(newFieldDefinition().name(FIELD_NODE).type(type).build())
                    .field(newFieldDefinition().name(FIELD_CURSOR).type(GraphQLString).build())
                    .build();
            GraphQLObjectType connectionType = newObject().name(tName + CONNECTION_SUFFIX)
                    .field(newFieldDefinition().name(FIELD_EDGES).type(GraphQLList.list(edgeType)).build())
                    .field(newFieldDefinition().name(FIELD_PAGE_INFO).type(pageInfoType).build())
                    .field(newFieldDefinition().name(FIELD_TOTAL_COUNT).type(GraphQLInt).build())
                    .build();
            queryBuilder.field(newFieldDefinition()
                    .name(fName + CONNECTION_SUFFIX)
                    .type(connectionType)
                    .argument(GraphQLArgument.newArgument().name(ARG_FIRST).type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_AFTER).type(GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_LAST).type(GraphQLInt).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_BEFORE).type(GraphQLString).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereTypes.get(table)).build())
                    .build());

            // Aggregate query
            queryBuilder.field(newFieldDefinition()
                    .name(fName + AGGREGATE_SUFFIX)
                    .type(newObject().name(tName + AGGREGATE_SUFFIX)
                            .field(newFieldDefinition().name(FIELD_COUNT).type(GraphQLInt).build())
                            .build())
                    .build());
        }

        // Build mutation type
        GraphQLObjectType.Builder mutationBuilder = newObject().name(TYPE_MUTATION);
        for (String table : schemaInfo.getTableNames()) {
            // Skip views — they are read-only
            if (schemaInfo.isView(table)) continue;

            String typeName = typeName(table);
            GraphQLObjectType type = types.get(table);
            GraphQLInputObjectType createInput = createInputs.get(table);

            mutationBuilder.field(newFieldDefinition()
                    .name(CREATE_PREFIX + typeName)
                    .type(type)
                    .argument(GraphQLArgument.newArgument().name(ARG_INPUT).type(createInput).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name(CREATE_MANY_PREFIX + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_INPUTS).type(GraphQLList.list(createInput)).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name(UPDATE_PREFIX + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereTypes.get(table)).build())
                    .argument(GraphQLArgument.newArgument().name(ARG_INPUT).type(createInput).build())
                    .build());
            mutationBuilder.field(newFieldDefinition()
                    .name(DELETE_PREFIX + typeName)
                    .type(GraphQLList.list(type))
                    .argument(GraphQLArgument.newArgument().name(ARG_WHERE).type(whereTypes.get(table)).build())
                    .build());
        }

        // Add stored procedure mutations
        for (var procEntry : schemaInfo.getStoredProcedures().entrySet()) {
            String procName = procEntry.getKey();
            SchemaInfo.ProcedureInfo proc = procEntry.getValue();
            String mutationName = CALL_PREFIX + typeName(procName);

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

        GraphQLObjectType mutationType = mutationBuilder.build();
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema()
                .query(queryBuilder.build());
        // Only add Mutation type if it has fields (empty Mutation is invalid in GraphQL)
        if (!mutationType.getFieldDefinitions().isEmpty()) {
            schemaBuilder.mutation(mutationType);
        }
        // Register extended scalar types
        schemaBuilder.additionalType(ExtendedScalars.GraphQLBigInteger);
        // Register enum types as additional types so they're discoverable via __type
        for (GraphQLEnumType enumType : enumTypeMap.values()) {
            schemaBuilder.additionalType(enumType);
        }
        // Register composite types as GraphQL object types
        for (var entry : schemaInfo.getCompositeTypes().entrySet()) {
            String typeName = typeName(entry.getKey());
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
        if (t.equals("bigint") || t.equals("int8")) return ExtendedScalars.GraphQLBigInteger;
        if (t.contains("int")) return GraphQLInt;
        if (t.contains("float") || t.contains("double") || t.contains("numeric") || t.contains("decimal") || t.contains("real")) return GraphQLFloat;
        if (t.contains("bool")) return GraphQLBoolean;
        return GraphQLString;
    }

    private GraphQLOutputType mapColumnType(String dbType) {
        if (dbType == null) return GraphQLString;
        String t = dbType.toLowerCase();
        if (t.equals("bigint") || t.equals("int8")) return ExtendedScalars.GraphQLBigInteger;
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
