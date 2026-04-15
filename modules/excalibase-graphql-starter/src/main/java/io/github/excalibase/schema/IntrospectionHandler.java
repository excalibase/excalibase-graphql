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
    private final GraphQLSchema schema;

    public IntrospectionHandler(SchemaInfo schemaInfo) {
        this.schema = buildSchema(schemaInfo);
        this.graphQL = GraphQL.newGraphQL(this.schema).build();
    }

    /** Expose the schema for external validation (e.g. graphql-java Validator). */
    public GraphQLSchema getSchema() { return schema; }

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
        Map<String, GraphQLInputObjectType> arrRelTypeMap = new LinkedHashMap<>();

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

        // String filter input — the full operator set supported by
        // FilterBuilder. Keep these in sync with the switch statement in
        // io.github.excalibase.compiler.FilterBuilder.buildFilterConditions().
        // Every operator present here MUST be handled there, and vice versa;
        // otherwise clients see operators the server silently ignores (or
        // vice versa).
        GraphQLInputObjectType stringFilter = GraphQLInputObjectType.newInputObject()
                .name("StringFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_ILIKE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINS).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_STARTS_WITH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_ENDS_WITH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_REGEX).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IREGEX).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // Tsvector filter input — two operators, both dispatched via
        // SqlDialect.fullTextSearchSql. Separate from StringFilterInput so
        // plain text columns don't accidentally expose the FTS surface
        // (which would emit invalid SQL against a non-tsvector column).
        //   search:    plainto_tsquery — raw user text, safe on any input
        //   webSearch: websearch_to_tsquery — Google-style "phrase" / OR / -
        GraphQLInputObjectType tsvectorFilter = GraphQLInputObjectType.newInputObject()
                .name("TsvectorFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_WEB_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_PHRASE_SEARCH).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_RAW_SEARCH).type(GraphQLString).build())
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
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLInt)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // Float filter input — numeric, decimal, real, double precision
        // columns get this so clients can filter with fractional values.
        // Previously these fell through to IntFilterInput which forced Int
        // binds and rejected decimals like `{ lt: 9.99 }`.
        GraphQLInputObjectType floatFilter = GraphQLInputObjectType.newInputObject()
                .name("FloatFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLFloat).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLFloat)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLFloat)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // Boolean filter input — boolean columns get this. Only equality and
        // null checks make sense (there's no "greater than false").
        GraphQLInputObjectType booleanFilter = GraphQLInputObjectType.newInputObject()
                .name("BooleanFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // DateTime filter input — timestamp, timestamptz, date, time, timetz
        // columns. Values are strings on the wire (ISO 8601 by convention)
        // and Postgres coerces them via paramCast. Supports the full range +
        // equality + list suite so users can filter by time windows.
        GraphQLInputObjectType dateTimeFilter = GraphQLInputObjectType.newInputObject()
                .name("DateTimeFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GT).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_GTE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LT).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_LTE).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // JSONB filter input. Each operator maps to a Postgres jsonb op or
        // function — see PostgresDialect.jsonPredicateSql for the full list.
        // The input values use graphql-java's ExtendedScalars.Json so
        // ObjectValue / ArrayValue arguments parse correctly; FilterBuilder
        // serializes them to compact JSON strings for Postgres to parse.
        GraphQLInputObjectType jsonFilter = GraphQLInputObjectType.newInputObject()
                .name("JsonFilterInput")
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINS).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_CONTAINED_BY).type(ExtendedScalars.Json).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_KEY).type(GraphQLString).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_KEYS).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_HAS_ANY_KEYS).type(GraphQLList.list(GraphQLString)).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                .build();

        // Per-enum filter input types. Enum columns (e.g. Postgres
        // `CREATE TYPE issue_status AS ENUM(...)`) were previously filtered
        // via StringFilterInput, which meant clients had to pass a plain
        // String and the server relied on the DB to coerce. That silently
        // accepts any string (losing compile-time safety) and prevents
        // autocomplete on enum values in codegen-generated clients.
        // Building a per-enum filter type gives clients enum-narrowed
        // eq/neq/in/notIn/isNull/isNotNull operators.
        Map<String, GraphQLInputObjectType> enumFilterMap = new LinkedHashMap<>();
        for (Map.Entry<String, GraphQLEnumType> entry : enumTypeMap.entrySet()) {
            GraphQLEnumType enumType = entry.getValue();
            GraphQLInputObjectType enumFilter = GraphQLInputObjectType.newInputObject()
                    .name(enumType.getName() + "FilterInput")
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_EQ).type(enumType).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NEQ).type(enumType).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IN).type(GraphQLList.list(enumType)).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_NOT_IN).type(GraphQLList.list(enumType)).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NULL).type(GraphQLBoolean).build())
                    .field(GraphQLInputObjectField.newInputObjectField().name(FILTER_IS_NOT_NULL).type(GraphQLBoolean).build())
                    .build();
            enumFilterMap.put(entry.getKey(), enumFilter);
        }

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
            // Forward FK fields: e.g., category_id → ShopifyCategories object
            for (var fkEntry : schemaInfo.getAllForwardFks().entrySet()) {
                if (!fkEntry.getKey().startsWith(table + ".")) continue;
                String fkFieldName = fkEntry.getKey().substring(table.length() + 1);
                String refTable = fkEntry.getValue().refTable();
                String refTypeName = typeName(refTable);
                typeBuilder.field(newFieldDefinition()
                        .name(fkFieldName)
                        .type(GraphQLTypeReference.typeRef(refTypeName))
                        .build());
            }

            // Reverse FK fields: e.g., products → [ShopifyProductVariants] list
            for (var revEntry : schemaInfo.getAllReverseFks().entrySet()) {
                if (!revEntry.getKey().startsWith(table + ".")) continue;
                String revFieldName = revEntry.getKey().substring(table.length() + 1);
                String childTable = revEntry.getValue().childTable();
                String childTypeName = typeName(childTable);
                typeBuilder.field(newFieldDefinition()
                        .name(revFieldName)
                        .type(GraphQLList.list(GraphQLTypeReference.typeRef(childTypeName)))
                        .build());
            }

            types.put(table, typeBuilder.build());

            // Where input type
            GraphQLInputObjectType.Builder whereBuilder = GraphQLInputObjectType.newInputObject()
                    .name(typeName + WHERE_INPUT_SUFFIX);
            for (String col : columns) {
                String colType = schemaInfo.getColumnType(table, col);
                String enumTypeName = schemaInfo.getEnumType(table, col);
                GraphQLInputObjectType filter;
                if (enumTypeName != null && enumFilterMap.containsKey(enumTypeName)) {
                    // Enum-backed column — use the per-enum filter so clients
                    // get narrowed operators (eq accepts only enum members).
                    filter = enumFilterMap.get(enumTypeName);
                } else if (isJsonType(colType)) {
                    filter = jsonFilter;
                } else if (isBooleanType(colType)) {
                    filter = booleanFilter;
                } else if (isDateTimeType(colType)) {
                    filter = dateTimeFilter;
                } else if (isFloatType(colType)) {
                    filter = floatFilter;
                } else if (isIntegerType(colType)) {
                    filter = intFilter;
                } else if ("tsvector".equalsIgnoreCase(colType)) {
                    filter = tsvectorFilter;
                } else {
                    filter = stringFilter;
                }
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
            // Reverse FK nested insert fields: e.g., testSchemaOrderItems: { data: [TestSchemaOrderItemsCreateInput!] }
            for (var revEntry : schemaInfo.getAllReverseFks().entrySet()) {
                if (!revEntry.getKey().startsWith(table + ".")) continue;
                String revFieldName = revEntry.getKey().substring(table.length() + 1);
                String childTypeName = typeName(revEntry.getValue().childTable());
                String arrRelTypeName = childTypeName + "ArrRelInsertInput";
                // Deduplicate: reuse existing ArrRelInsertInput if the same child type appears across multiple parents
                GraphQLInputObjectType arrRelType = arrRelTypeMap.computeIfAbsent(arrRelTypeName, name ->
                        GraphQLInputObjectType.newInputObject()
                                .name(name)
                                .field(GraphQLInputObjectField.newInputObjectField()
                                        .name("data")
                                        .type(GraphQLList.list(GraphQLTypeReference.typeRef(childTypeName + CREATE_INPUT_SUFFIX)))
                                        .build())
                                .build());
                createBuilder.field(GraphQLInputObjectField.newInputObjectField()
                        .name(revFieldName)
                        .type(arrRelType)
                        .build());
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
        // Register ArrRelInsertInput types for nested FK insert validation
        for (GraphQLInputObjectType arrRelType : arrRelTypeMap.values()) {
            schemaBuilder.additionalType(arrRelType);
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

    /**
     * Kept for compatibility — callers should prefer the narrower
     * {@link #isIntegerType(String)} and {@link #isFloatType(String)} which
     * distinguish the two numeric families the new filter input types need.
     */
    private boolean isNumericType(String dbType) {
        return isIntegerType(dbType) || isFloatType(dbType);
    }

    private boolean isIntegerType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        // smallint, int2, int, integer, int4, bigint, int8, serial, bigserial
        return t.equals("smallint") || t.equals("int2")
                || t.equals("int") || t.equals("integer") || t.equals("int4")
                || t.equals("bigint") || t.equals("int8")
                || t.equals("serial") || t.equals("serial4")
                || t.equals("bigserial") || t.equals("serial8")
                || t.equals("smallserial") || t.equals("serial2");
    }

    private boolean isFloatType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        // numeric, decimal, real, float4, double precision, float8, money
        return t.equals("numeric") || t.equals("decimal")
                || t.equals("real") || t.equals("float4")
                || t.equals("double precision") || t.equals("float8")
                || t.equals("money");
    }

    private boolean isBooleanType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        return t.equals("bool") || t.equals("boolean");
    }

    private boolean isDateTimeType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        return t.equals("date")
                || t.equals("time") || t.equals("timetz")
                || t.equals("time with time zone") || t.equals("time without time zone")
                || t.equals("timestamp") || t.equals("timestamptz")
                || t.equals("timestamp with time zone") || t.equals("timestamp without time zone")
                || t.equals("interval");
    }

    private boolean isJsonType(String dbType) {
        if (dbType == null) return false;
        String t = dbType.toLowerCase();
        return t.equals("json") || t.equals("jsonb");
    }

}
