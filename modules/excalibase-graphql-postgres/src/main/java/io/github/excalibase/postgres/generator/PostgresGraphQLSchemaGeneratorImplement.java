package io.github.excalibase.postgres.generator;

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
import io.github.excalibase.constant.ColumnTypeConstant;
import io.github.excalibase.constant.FieldConstant;
import io.github.excalibase.constant.GraphqlConstant;
import io.github.excalibase.constant.MutationConstant;
import io.github.excalibase.postgres.constant.PostgresTypeOperator;
import io.github.excalibase.constant.SupportedDatabaseConstant;

import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.CompositeTypeAttribute;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.scalar.JsonScalar;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static graphql.Scalars.*;

/**
 * PostgreSQL implementation of GraphQL schema generator.
 * 
 * <p>Generates complete GraphQL schemas from PostgreSQL table metadata including:
 * query fields with filtering/pagination, mutation fields for CRUD operations,
 * and connection types following Relay specification.</p>
 */
@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresGraphQLSchemaGeneratorImplement implements IGraphQLSchemaGenerator {
    private static final Logger log = LoggerFactory.getLogger(PostgresGraphQLSchemaGeneratorImplement.class);
    private IDatabaseSchemaReflector schemaReflector;

    public void setSchemaReflector(IDatabaseSchemaReflector schemaReflector) {
        this.schemaReflector = schemaReflector;
    }

    private IDatabaseSchemaReflector getSchemaReflector() {
        return schemaReflector;
    }

    @Override
    public GraphQLSchema generateSchema(Map<String, TableInfo> tables) {
        if (tables == null || tables.isEmpty()) {
            log.info("No tables found, generating minimal schema with health check");
            return createMinimalSchema();
        }
        
        IDatabaseSchemaReflector reflector = getSchemaReflector();
        List<CustomEnumInfo> customEnums = new ArrayList<>();
        List<CustomCompositeTypeInfo> customComposites = new ArrayList<>();
        
        if (reflector != null) {
            customEnums = reflector.getCustomEnumTypes();
            customComposites = reflector.getCustomCompositeTypes();
            log.info("PostgreSQL schema generation: found {} custom enum types and {} custom composite types", 
                    customEnums.size(), customComposites.size());
        } else {
            log.debug("PostgreSQL schema generation: running in testing mode, skipping custom types");
        }
        
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema();

        // Create common types
        GraphQLEnumType orderDirectionEnum = createOrderDirectionEnum();
        GraphQLObjectType pageInfoType = createPageInfoType();

        schemaBuilder.additionalType(orderDirectionEnum);
        schemaBuilder.additionalType(pageInfoType);

        // Create filter input types
        Map<String, GraphQLInputObjectType> filterInputTypes = createFilterInputTypes();
        for (GraphQLInputObjectType filterType : filterInputTypes.values()) {
            schemaBuilder.additionalType(filterType);
        }

        // Add custom enum types to schema
        Map<String, GraphQLEnumType> customEnumTypes = new HashMap<>();
        for (CustomEnumInfo enumInfo : customEnums) {
            GraphQLEnumType enumType = createGraphQLEnumType(enumInfo);
            customEnumTypes.put(enumInfo.getName(), enumType);
            schemaBuilder.additionalType(enumType);
        }

        // Add custom composite types to schema
        Map<String, GraphQLObjectType> customCompositeTypes = new HashMap<>();
        for (CustomCompositeTypeInfo compositeInfo : customComposites) {
            GraphQLObjectType objectType = createGraphQLObjectType(compositeInfo, customEnumTypes, customCompositeTypes);
            customCompositeTypes.put(compositeInfo.getName(), objectType);
            schemaBuilder.additionalType(objectType);
        }

        // Create type definitions for each table with custom types support
        Map<String, GraphQLInputObjectType> tableFilterTypes = new HashMap<>();
        createTableTypesWithCustomTypes(schemaBuilder, tables, pageInfoType, filterInputTypes, 
                                       tableFilterTypes, customEnumTypes, customCompositeTypes);

        // Create Query type
        GraphQLObjectType queryType = createQueryType(tables, tableFilterTypes);
        schemaBuilder.query(queryType);

        // Create Mutation type only if there are tables (not just views)
        boolean hasTables = tables.values().stream().anyMatch(table -> !table.isView());
        if (hasTables) {
            GraphQLObjectType mutationType = createMutationType(tables);
            schemaBuilder.mutation(mutationType);
        }

        return schemaBuilder.build();
    }

    /**
     * Creates a minimal GraphQL schema with just a health check query when no tables are found.
     * This provides graceful degradation instead of throwing an exception.
     */
    private GraphQLSchema createMinimalSchema() {
        return GraphQLSchema.newSchema()
            .query(GraphQLObjectType.newObject()
                .name(GraphqlConstant.QUERY)
                .field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(GraphqlConstant.HEALTH)
                    .type(GraphQLString)
                    .staticValue("Schema is empty but service is running")
                    .build())
                .build())
            .build();
    }

    private GraphQLEnumType createOrderDirectionEnum() {
        return GraphQLEnumType.newEnum()
                .name(GraphqlConstant.ORDER_DIRECTION)
                .description("Possible directions in which to order a list of items")
                .value(GraphqlConstant.ASC, GraphqlConstant.ASC, "Sort in ascending order")
                .value(GraphqlConstant.DESC, GraphqlConstant.DESC, "Sort in descending order")
                .build();
    }

    private GraphQLObjectType createPageInfoType() {
        return GraphQLObjectType.newObject()
                .name(FieldConstant.PAGE_INFO)
                .description("Information about pagination in a connection")
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.HAS_NEXT_PAGE)
                        .type(GraphQLBoolean)
                        .description("When paginating forwards, are there more items?")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.HAS_PREVIOUS_PAGE)
                        .type(GraphQLBoolean)
                        .description("When paginating backwards, are there more items?")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.START_CURSOR)
                        .type(GraphQLString)
                        .description("The cursor to continue from when paginating backwards")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.END_CURSOR)
                        .type(GraphQLString)
                        .description("The cursor to continue from when paginating forwards")
                        .build())
                .build();
    }

    private void createTableTypes(GraphQLSchema.Builder schemaBuilder, Map<String, TableInfo> tables, GraphQLObjectType pageInfoType, Map<String, GraphQLInputObjectType> filterInputTypes, Map<String, GraphQLInputObjectType> tableFilterTypes) {
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();

            // Create ObjectType for the table with reverse relationships
            GraphQLObjectType nodeType = createNodeType(tableName, tableInfo, tables);
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

            // Create filter input type for the table
            GraphQLInputObjectType filterType = createFilterInputTypeForTable(tableName, tableInfo, filterInputTypes);
            tableFilterTypes.put(tableName, filterType);
            schemaBuilder.additionalType(filterType);
        }
    }

    private GraphQLObjectType createNodeType(String tableName, TableInfo tableInfo) {
        return createNodeType(tableName, tableInfo, new HashMap<>());
    }
    
    private GraphQLObjectType createNodeType(String tableName, TableInfo tableInfo, Map<String, TableInfo> allTables) {
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

        // Add fields for forward relationships (foreign keys from this table)
        for (ForeignKeyInfo fk : tableInfo.getForeignKeys()) {
            GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(fk.getReferencedTable().toLowerCase())
                    .type(GraphQLTypeReference.typeRef(fk.getReferencedTable()))
                    .description("Relationship to " + fk.getReferencedTable());

            typeBuilder.field(fieldBuilder.build());
        }
        
        // Add fields for reverse relationships (foreign keys from other tables pointing to this table)
        if (!allTables.isEmpty()) {
            log.debug("Processing reverse relationships for table: {}", tableName);
            for (Map.Entry<String, TableInfo> entry : allTables.entrySet()) {
                String otherTableName = entry.getKey();
                TableInfo otherTableInfo = entry.getValue();
                
                // Skip self-references and views
                if (otherTableName.equals(tableName) || otherTableInfo.isView()) {
                    log.debug("Skipping table {} (self-reference: {}, view: {})", otherTableName, 
                            otherTableName.equals(tableName), otherTableInfo.isView());
                    continue;
                }
                
                log.debug("Checking table {} for foreign keys referencing {}", otherTableName, tableName);
                // Find foreign keys in other tables that reference this table
                for (ForeignKeyInfo fk : otherTableInfo.getForeignKeys()) {
                    log.debug("Found foreign key: {} -> {}.{}", fk.getColumnName(), fk.getReferencedTable(), fk.getReferencedColumn());
                    if (fk.getReferencedTable().equalsIgnoreCase(tableName)) {
                        // Add reverse relationship field (plural name since it's one-to-many)
                        String reverseFieldName = otherTableName.toLowerCase();
                        // Make it plural if it doesn't end with 's'
                        if (!reverseFieldName.endsWith("s")) {
                            reverseFieldName += "s";
                        }
                        
                        log.info("Adding reverse relationship field '{}' to table '{}' referencing table '{}'", 
                                reverseFieldName, tableName, otherTableName);
                        
                        GraphQLFieldDefinition.Builder reverseFieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                                .name(reverseFieldName)
                                .type(new GraphQLList(GraphQLTypeReference.typeRef(otherTableName)))
                                .description("Reverse relationship to " + otherTableName + " via " + fk.getColumnName());

                        typeBuilder.field(reverseFieldBuilder.build());
                    }
                }
            }
        }

        return typeBuilder.build();
    }

    private GraphQLObjectType createEdgeType(String tableName, GraphQLObjectType nodeType) {
        return GraphQLObjectType.newObject()
                .name(tableName + "Edge")
                .description("An edge in a connection for " + tableName)
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.NODE)
                        .type(nodeType)
                        .description("The item at the end of the edge")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.CURSOR)
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
                        .name(FieldConstant.EDGES)
                        .type(new GraphQLList(edgeType))
                        .description("A list of edges")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.PAGE_INFO)
                        .type(new GraphQLNonNull(pageInfoType))
                        .description("Information to aid in pagination")
                        .build())
                .field(GraphQLFieldDefinition.newFieldDefinition()
                        .name(FieldConstant.TOTAL_COUNT)
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

    private GraphQLObjectType createQueryType(Map<String, TableInfo> tables, Map<String, GraphQLInputObjectType> tableFilterTypes) {
        GraphQLObjectType.Builder queryBuilder = GraphQLObjectType.newObject()
                .name(GraphqlConstant.QUERY)
                .description("Root query type");

        // Add query fields for each table
        for (String tableName : tables.keySet()) {
            TableInfo tableInfo = tables.get(tableName);
            addQueryFields(queryBuilder, tableName, tableInfo, tableFilterTypes.get(tableName));
            addConnectionFields(queryBuilder, tableName, tableInfo, tableFilterTypes);
        }

        return queryBuilder.build();
    }

    private void addQueryFields(GraphQLObjectType.Builder queryBuilder, String tableName, TableInfo tableInfo, GraphQLInputObjectType filterType) {
        GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName.toLowerCase())
                .type(new GraphQLList(GraphQLTypeReference.typeRef(tableName)))
                .description("Query all records from " + tableName);

        // Add the where argument using the table's filter type
        fieldBuilder.argument(GraphQLArgument.newArgument()
            .name(GraphqlConstant.WHERE)
            .type(filterType)
            .description("Filter conditions for " + tableName)
            .build());
        
        // Add OR filter argument
        fieldBuilder.argument(GraphQLArgument.newArgument()
            .name(GraphqlConstant.OR)
            .type(new GraphQLList(filterType))
            .description("OR conditions for " + tableName)
            .build());

        // Add filtering arguments (keep legacy for backward compatibility)
        for (ColumnInfo column : tableInfo.getColumns()) {
            addFilteringArguments(fieldBuilder, column, filterType);
        }

        // Add pagination arguments
        addPaginationArguments(fieldBuilder);

        // Add orderBy argument
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.ORDER_BY)
                .type(GraphQLTypeReference.typeRef(tableName + "OrderByInput"))
                .description("Order results by specified fields")
                .build());

        queryBuilder.field(fieldBuilder.build());
    }

    private void addConnectionFields(GraphQLObjectType.Builder queryBuilder, String tableName, TableInfo tableInfo, Map<String, GraphQLInputObjectType> tableFilterTypes) {
        GraphQLFieldDefinition.Builder connectionFieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                .name(tableName.toLowerCase() + "Connection")
                .type(GraphQLTypeReference.typeRef(tableName + "Connection"))
                .description("Connection to " + tableName + " records for cursor-based pagination following the Relay specification");

        // Add the where argument using the table's filter type
        GraphQLInputObjectType tableFilterType = tableFilterTypes.get(tableName);
        connectionFieldBuilder.argument(GraphQLArgument.newArgument()
            .name(GraphqlConstant.WHERE)
            .type(tableFilterType)
            .description("Filter conditions for " + tableName)
            .build());
        
        // Add OR filter argument
        connectionFieldBuilder.argument(GraphQLArgument.newArgument()
            .name(GraphqlConstant.OR)
            .type(new GraphQLList(tableFilterType))
            .description("OR conditions for " + tableName)
            .build());

        // Add ordering to connection fields
        connectionFieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.ORDER_BY)
                .type(GraphQLTypeReference.typeRef(tableName + "OrderByInput"))
                .description("Order results by specified fields")
                .build());

        // Add cursor-based pagination arguments
        addCursorPaginationArguments(connectionFieldBuilder);

        queryBuilder.field(connectionFieldBuilder.build());
    }

    private void addFilteringArguments(GraphQLFieldDefinition.Builder fieldBuilder, ColumnInfo column, GraphQLInputObjectType filterType) {
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
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_CONTAINS)
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " contains text")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_STARTS_WITH)
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " starts with text")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_ENDS_WITH)
                    .type(GraphQLString)
                    .description("Filter where " + column.getName() + " ends with text")
                    .build());
        }

        // For numeric columns, add comparison operators
        if (argType == GraphQLInt || argType == GraphQLFloat) {
            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_GT)
                    .type(argType)
                    .description("Filter where " + column.getName() + " is greater than")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_GTE)
                    .type(argType)
                    .description("Filter where " + column.getName() + " is greater than or equal")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_LT)
                    .type(argType)
                    .description("Filter where " + column.getName() + " is less than")
                    .build());

            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_" + FieldConstant.OPERATOR_LTE)
                    .type(argType)
                    .description("Filter where " + column.getName() + " is less than or equal")
                    .build());
        }

        // Add filter arguments
        if (filterType != null) {
            fieldBuilder.argument(GraphQLArgument.newArgument()
                    .name(column.getName() + "_filter")
                    .type(filterType)
                    .description("Filter by " + column.getName())
                    .build());
        }
    }

    private void addPaginationArguments(GraphQLFieldDefinition.Builder fieldBuilder) {
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.LIMIT)
                .type(GraphQLInt)
                .description("Maximum number of records to return")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.OFFSET)
                .type(GraphQLInt)
                .description("Number of records to skip")
                .build());
    }

    private void addCursorPaginationArguments(GraphQLFieldDefinition.Builder fieldBuilder) {
        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.FIRST)
                .type(GraphQLInt)
                .description("Returns the first n elements from the list")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.AFTER)
                .type(GraphQLString)
                .description("Returns elements after the provided cursor")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.LAST)
                .type(GraphQLInt)
                .description("Returns the last n elements from the list")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.BEFORE)
                .type(GraphQLString)
                .description("Returns elements before the provided cursor")
                .build());

        fieldBuilder.argument(GraphQLArgument.newArgument()
                .name(FieldConstant.OFFSET)
                .type(GraphQLInt)
                .description("Number of records to skip (fallback when cursor parameters are not used)")
                .build());
    }

    private GraphQLObjectType createMutationType(Map<String, TableInfo> tables) {
        GraphQLObjectType.Builder mutationBuilder = GraphQLObjectType.newObject()
                .name(GraphqlConstant.MUTATION)
                .description("Root mutation type");

        // Add mutation fields only for tables, not views
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();
            
            // Only add mutations for tables, not views
            if (!tableInfo.isView()) {
                addMutationFields(mutationBuilder, tableName, tableInfo, tables);
            }
        }

        return mutationBuilder.build();
    }

    private void addMutationFields(GraphQLObjectType.Builder mutationBuilder, String tableName, TableInfo tableInfo, Map<String, TableInfo> tables) {
        GraphQLInputObjectType createInputType = createInputTypes(tableName, tableInfo, false);
        GraphQLInputObjectType updateInputType = createInputTypes(tableName, tableInfo, true);

        // 1. Create mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(MutationConstant.CREATE_PREFIX + capitalize(tableName))
                .description(String.format(MutationConstant.CREATE_DESCRIPTION_TEMPLATE, tableName))
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name(GraphqlConstant.INPUT)
                        .type(new GraphQLNonNull(createInputType))
                        .build())
                .build());

        // 2. Update mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(MutationConstant.UPDATE_PREFIX + capitalize(tableName))
                .description(String.format(MutationConstant.UPDATE_DESCRIPTION_TEMPLATE, tableName))
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name(GraphqlConstant.INPUT)
                        .type(new GraphQLNonNull(updateInputType))
                        .build())
                .build());

        // 3. Delete mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(MutationConstant.DELETE_PREFIX + capitalize(tableName))
                .description(String.format(MutationConstant.DELETE_DESCRIPTION_TEMPLATE, tableName))
                .type(GraphQLBoolean)
                .argument(GraphQLArgument.newArgument()
                        .name(GraphqlConstant.ID)
                        .type(new GraphQLNonNull(GraphQLID))
                        .description("Primary key of record to delete")
                        .build())
                .build());

        // 4. Bulk create mutation
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(MutationConstant.CREATE_MANY_PREFIX + capitalize(tableName) + MutationConstant.BULK_SUFFIX)
                .description(String.format(MutationConstant.CREATE_MANY_DESCRIPTION_TEMPLATE, tableName))
                .type(new GraphQLList(GraphQLTypeReference.typeRef(tableName)))
                .argument(GraphQLArgument.newArgument()
                        .name(GraphqlConstant.INPUTS)
                        .type(new GraphQLNonNull(new GraphQLList(new GraphQLNonNull(createInputType))))
                        .description("Array of " + tableName + " records to create")
                        .build())
                .build());

        // 5. Create with relationships mutation
        GraphQLInputObjectType relationshipInputType = createRelationshipInputType(tableName, tableInfo, tables);
        mutationBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                .name(MutationConstant.CREATE_PREFIX + capitalize(tableName) + MutationConstant.WITH_RELATIONS_SUFFIX)
                .description(String.format(MutationConstant.CREATE_WITH_RELATIONS_DESCRIPTION_TEMPLATE, tableName))
                .type(GraphQLTypeReference.typeRef(tableName))
                .argument(GraphQLArgument.newArgument()
                        .name(GraphqlConstant.INPUT)
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
        log.debug("Request db type: {}", type);
        
        // Handle array types
        if (type.contains(ColumnTypeConstant.ARRAY_SUFFIX)) {
            String baseType = type.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
            GraphQLOutputType elementType = mapDatabaseTypeToGraphQLType(baseType);
            return new GraphQLList(elementType);
        }
        
        // Integer types
        if (PostgresTypeOperator.isIntegerType(type)) {
            return GraphQLInt;
        }
        
        // Floating point types
        else if (PostgresTypeOperator.isFloatingPointType(type)) {
            return GraphQLFloat;
        }
        
        // Boolean types
        else if (PostgresTypeOperator.isBooleanType(type)) {
            return GraphQLBoolean;
        }
        
        // JSON types
        else if (PostgresTypeOperator.isJsonType(type)) {
            return JsonScalar.JSON;
        }
        
        // UUID types
        else if (type.contains(ColumnTypeConstant.UUID)) {
            return GraphQLID;
        }
        
        // Date/Time types (including enhanced ones)
        else if (PostgresTypeOperator.isDateTimeType(type)) {
            return GraphQLString;
        }
        
        // Binary and network types
        else if (type.contains(ColumnTypeConstant.BYTEA) || type.contains(ColumnTypeConstant.INET) ||
                type.contains(ColumnTypeConstant.CIDR) || type.contains(ColumnTypeConstant.MACADDR) ||
                type.contains(ColumnTypeConstant.MACADDR8)) {
            return GraphQLString;
        }
        
        // Bit types
        else if (type.contains(ColumnTypeConstant.BIT) || type.contains(ColumnTypeConstant.VARBIT)) {
            return GraphQLString;
        }
        
        // XML type
        else if (type.contains(ColumnTypeConstant.XML)) {
            return GraphQLString;
        }
        
        // Default to string for any unhandled types
        else {
            log.debug("Unmapped database type '{}', defaulting to GraphQLString", type);
            return GraphQLString;
        }
    }

    private GraphQLInputType mapDatabaseTypeToGraphQLInputType(String dbType) {
        return (GraphQLInputType) mapDatabaseTypeToGraphQLType(dbType);
    }

    /**
     * Creates filter input types for different scalar types
     */
    private Map<String, GraphQLInputObjectType> createFilterInputTypes() {
        Map<String, GraphQLInputObjectType> filterTypes = new HashMap<>();
        
        // String filter type
        GraphQLInputObjectType stringFilter = GraphQLInputObjectType.newInputObject()
            .name(GraphqlConstant.STRING_FILTER)
            .description("String filter options")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_EQ)
                .type(GraphQLString)
                .description("Equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_NEQ)
                .type(GraphQLString)
                .description("Not equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_CONTAINS)
                .type(GraphQLString)
                .description("Contains text")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_STARTS_WITH)
                .type(GraphQLString)
                .description("Starts with text")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_ENDS_WITH)
                .type(GraphQLString)
                .description("Ends with text")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_LIKE)
                .type(GraphQLString)
                .description("Like pattern")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_ILIKE)
                .type(GraphQLString)
                .description("Case-insensitive like pattern")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_IS_NULL)
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_IS_NOT_NULL)
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_IN)
                .type(new GraphQLList(GraphQLString))
                .description("In list of values")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(FieldConstant.OPERATOR_NOT_IN)
                .type(new GraphQLList(GraphQLString))
                .description("Not in list of values")
                .build())
            .build();
        filterTypes.put("StringFilter", stringFilter);
        
        // Integer filter type
        GraphQLInputObjectType intFilter = GraphQLInputObjectType.newInputObject()
            .name("IntFilter")
            .description("Integer filter options")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("eq")
                .type(GraphQLInt)
                .description("Equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("neq")
                .type(GraphQLInt)
                .description("Not equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gt")
                .type(GraphQLInt)
                .description("Greater than")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gte")
                .type(GraphQLInt)
                .description("Greater than or equal")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lt")
                .type(GraphQLInt)
                .description("Less than")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lte")
                .type(GraphQLInt)
                .description("Less than or equal")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNull")
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNotNull")
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("in")
                .type(new GraphQLList(GraphQLInt))
                .description("In list of values")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("notIn")
                .type(new GraphQLList(GraphQLInt))
                .description("Not in list of values")
                .build())
            .build();
        filterTypes.put("IntFilter", intFilter);
        
        // Float filter type
        GraphQLInputObjectType floatFilter = GraphQLInputObjectType.newInputObject()
            .name("FloatFilter")
            .description("Float filter options")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("eq")
                .type(GraphQLFloat)
                .description("Equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("neq")
                .type(GraphQLFloat)
                .description("Not equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gt")
                .type(GraphQLFloat)
                .description("Greater than")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gte")
                .type(GraphQLFloat)
                .description("Greater than or equal")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lt")
                .type(GraphQLFloat)
                .description("Less than")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lte")
                .type(GraphQLFloat)
                .description("Less than or equal")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNull")
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNotNull")
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("in")
                .type(new GraphQLList(GraphQLFloat))
                .description("In list of values")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("notIn")
                .type(new GraphQLList(GraphQLFloat))
                .description("Not in list of values")
                .build())
            .build();
        filterTypes.put("FloatFilter", floatFilter);
        
        // Boolean filter type
        GraphQLInputObjectType booleanFilter = GraphQLInputObjectType.newInputObject()
            .name("BooleanFilter")
            .description("Boolean filter options")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("eq")
                .type(GraphQLBoolean)
                .description("Equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("neq")
                .type(GraphQLBoolean)
                .description("Not equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNull")
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNotNull")
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .build();
        filterTypes.put("BooleanFilter", booleanFilter);
        
        // DateTime filter type for timestamps and dates
        GraphQLInputObjectType dateTimeFilter = GraphQLInputObjectType.newInputObject()
            .name("DateTimeFilter")
            .description("DateTime filter options for timestamps and dates")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("eq")
                .type(GraphQLString)
                .description("Equals (ISO format: YYYY-MM-DD or YYYY-MM-DD HH:MM:SS)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("neq")
                .type(GraphQLString)
                .description("Not equals")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gt")
                .type(GraphQLString)
                .description("Greater than (after)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("gte")
                .type(GraphQLString)
                .description("Greater than or equal (after or on)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lt")
                .type(GraphQLString)
                .description("Less than (before)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("lte")
                .type(GraphQLString)
                .description("Less than or equal (before or on)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNull")
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNotNull")
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("in")
                .type(new GraphQLList(GraphQLString))
                .description("In list of dates/timestamps")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("notIn")
                .type(new GraphQLList(GraphQLString))
                .description("Not in list of dates/timestamps")
                .build())
            .build();
        filterTypes.put("DateTimeFilter", dateTimeFilter);
        
        // JSON filter type for JSON/JSONB columns
        GraphQLInputObjectType jsonFilter = GraphQLInputObjectType.newInputObject()
            .name("JSONFilter")
            .description("JSON filter options for JSON and JSONB columns")
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("eq")
                .type(JsonScalar.JSON)
                .description("Equals JSON value")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("neq")
                .type(JsonScalar.JSON)
                .description("Not equals JSON value")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("contains")
                .type(GraphQLString)
                .description("Text search within JSON/JSONB content")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("containedBy")
                .type(JsonScalar.JSON)
                .description("JSON contained by (<@)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("hasKey")
                .type(GraphQLString)
                .description("Has key (?)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("hasKeys")
                .type(new GraphQLList(GraphQLString))
                .description("Has all keys (?&)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("hasAnyKeys")
                .type(new GraphQLList(GraphQLString))
                .description("Has any of the keys (?|)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("path")
                .type(GraphQLString)
                .description("JSON path exists (#>)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("pathText")
                .type(GraphQLString)
                .description("JSON path as text (#>>)")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNull")
                .type(GraphQLBoolean)
                .description("Is null")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name("isNotNull")
                .type(GraphQLBoolean)
                .description("Is not null")
                .build())
            .build();
        filterTypes.put("JSONFilter", jsonFilter);
        
        return filterTypes;
    }

    /**
     * Gets the filter type name based on database column type
     */
    private String getFilterTypeNameForColumn(String dbType) {
        String type = dbType.toLowerCase();
        
        // Handle array types - use the base type's filter
        if (type.contains(ColumnTypeConstant.ARRAY_SUFFIX)) {
            String baseType = type.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
            return getFilterTypeNameForColumn(baseType);
        }
        
        // JSON/JSONB types
        if (type.contains(ColumnTypeConstant.JSON) || type.contains(ColumnTypeConstant.JSONB)) {
            return "JSONFilter";
        }
        
        // Date/Time types (including enhanced ones)
        else if (type.contains(ColumnTypeConstant.TIMESTAMP) || type.contains(ColumnTypeConstant.TIMESTAMPTZ) ||
                type.contains(ColumnTypeConstant.DATE) || type.contains(ColumnTypeConstant.TIME) ||
                type.contains(ColumnTypeConstant.TIMETZ) || type.contains(ColumnTypeConstant.INTERVAL)) {
            return "DateTimeFilter";
        }
        
        // Integer types
        else if (PostgresTypeOperator.isIntegerType(type)) {
            return "IntFilter";
        }
        
        // Floating point types
        else if (PostgresTypeOperator.isFloatingPointType(type)) {
            return "FloatFilter";
        }
        
        // Boolean types
        else if (PostgresTypeOperator.isBooleanType(type)) {
            return "BooleanFilter";
        }
        
        // Default to string filter for text, binary, network, and other types
        else {
            return "StringFilter";
        }
    }

    /**
     * Creates a filter input type for a specific table
     */
    private GraphQLInputObjectType createFilterInputTypeForTable(String tableName, TableInfo tableInfo, Map<String, GraphQLInputObjectType> filterInputTypes) {
        GraphQLInputObjectType.Builder filterBuilder = GraphQLInputObjectType.newInputObject()
            .name(tableName + "Filter")
            .description("Filter input for " + tableName);
        
        // Add filter fields for each column
        for (ColumnInfo column : tableInfo.getColumns()) {
            String filterTypeName = getFilterTypeNameForColumn(column.getType());
            
            if (filterInputTypes.containsKey(filterTypeName)) {
                filterBuilder.field(GraphQLInputObjectField.newInputObjectField()
                    .name(column.getName())
                    .type(filterInputTypes.get(filterTypeName))
                    .description("Filter options for " + column.getName())
                    .build());
            }
        }
        
        // Build the filter type first to get a reference to it
        GraphQLInputObjectType tableFilterType = filterBuilder.build();
        
        // Add OR field that accepts a list of the same filter type
        filterBuilder.field(GraphQLInputObjectField.newInputObjectField()
            .name("or")
            .type(new GraphQLList(GraphQLTypeReference.typeRef(tableName + "Filter")))
            .description("OR conditions")
            .build());
        
        return filterBuilder.build();
    }



    /**
     * Creates a GraphQL enum type from PostgreSQL custom enum info.
     */
    private GraphQLEnumType createGraphQLEnumType(CustomEnumInfo enumInfo) {
        GraphQLEnumType.Builder enumBuilder = GraphQLEnumType.newEnum()
                .name(toGraphQLTypeName(enumInfo.getName()))
                .description("Custom enum type: " + enumInfo.getName());

        for (String value : enumInfo.getValues()) {
            enumBuilder.value(value.toUpperCase(), value, "Enum value: " + value);
        }

        return enumBuilder.build();
    }

    /**
     * Creates a GraphQL object type from PostgreSQL custom composite info.
     */
    private GraphQLObjectType createGraphQLObjectType(CustomCompositeTypeInfo compositeInfo,
                                                     Map<String, GraphQLEnumType> customEnumTypes,
                                                     Map<String, GraphQLObjectType> customCompositeTypes) {
        GraphQLObjectType.Builder objectBuilder = GraphQLObjectType.newObject()
                .name(toGraphQLTypeName(compositeInfo.getName()))
                .description("Custom composite type: " + compositeInfo.getName());

        for (CompositeTypeAttribute attribute : compositeInfo.getAttributes()) {
            GraphQLOutputType fieldType = mapDatabaseTypeToGraphQLType(attribute.getType(), 
                                                                       customEnumTypes, customCompositeTypes);
            objectBuilder.field(GraphQLFieldDefinition.newFieldDefinition()
                    .name(attribute.getName())
                    .type(fieldType)
                    .description("Attribute: " + attribute.getName())
                    .build());
        }

        return objectBuilder.build();
    }

    /**
     * Maps database types to GraphQL types with custom type support.
     */
    private GraphQLOutputType mapDatabaseTypeToGraphQLType(String type,
                                                          Map<String, GraphQLEnumType> customEnumTypes,
                                                          Map<String, GraphQLObjectType> customCompositeTypes) {
        // Check for custom enum types first
        if (PostgresTypeOperator.isCustomEnumType(type) && customEnumTypes.containsKey(type)) {
            return customEnumTypes.get(type);
        }

        // Check for custom composite types
        if (PostgresTypeOperator.isCustomCompositeType(type) && customCompositeTypes.containsKey(type)) {
            return customCompositeTypes.get(type);
        }

        // Fall back to standard type mapping
        return mapDatabaseTypeToGraphQLType(type);
    }

    /**
     * Converts PostgreSQL type name to GraphQL type name (PascalCase).
     */
    private String toGraphQLTypeName(String postgresTypeName) {
        // Split by underscore and capitalize each part
        String[] parts = postgresTypeName.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    result.append(part.substring(1).toLowerCase());
                }
            }
        }
        return result.toString();
    }

    /**
     * Enhanced version of createTableTypes that supports custom types.
     */
    private void createTableTypesWithCustomTypes(GraphQLSchema.Builder schemaBuilder,
                                               Map<String, TableInfo> tables,
                                               GraphQLObjectType pageInfoType,
                                               Map<String, GraphQLInputObjectType> filterInputTypes,
                                               Map<String, GraphQLInputObjectType> tableFilterTypes,
                                               Map<String, GraphQLEnumType> customEnumTypes,
                                               Map<String, GraphQLObjectType> customCompositeTypes) {
        
        for (Map.Entry<String, TableInfo> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            TableInfo tableInfo = entry.getValue();

            // Create table type with custom type support
            GraphQLObjectType tableType = createNodeTypeWithCustomTypes(tableName, tableInfo, tables, customEnumTypes, customCompositeTypes);
            schemaBuilder.additionalType(tableType);

            // Create edge type first (needs the node type)
            GraphQLObjectType edgeType = createEdgeType(tableName, tableType);
            schemaBuilder.additionalType(edgeType);

            // Create connection type (needs the edge type)
            GraphQLObjectType connectionType = createConnectionType(tableName, edgeType, pageInfoType);
            schemaBuilder.additionalType(connectionType);

            // Create filter input type for the table
            GraphQLInputObjectType tableFilterType = createFilterInputTypeForTable(tableName, tableInfo, filterInputTypes);
            tableFilterTypes.put(tableName, tableFilterType);
            schemaBuilder.additionalType(tableFilterType);

            // Create order by input type
            GraphQLInputObjectType orderByType = createOrderByInput(tableName, tableInfo);
            schemaBuilder.additionalType(orderByType);

            // Note: Input types for mutations are created by createMutationType, not here
            // This avoids duplicate input type creation
        }
    }
    
    /**
     * Creates a GraphQL object type for a table with custom type support.
     */
    private GraphQLObjectType createNodeTypeWithCustomTypes(String tableName, TableInfo tableInfo, Map<String, TableInfo> allTables,
                                                           Map<String, GraphQLEnumType> customEnumTypes,
                                                           Map<String, GraphQLObjectType> customCompositeTypes) {
        GraphQLObjectType.Builder typeBuilder = GraphQLObjectType.newObject()
                .name(tableName)
                .description("Type for table " + tableName);

        // Add fields for each column with custom type support
        for (ColumnInfo column : tableInfo.getColumns()) {
            GraphQLOutputType fieldType = mapDatabaseTypeToGraphQLType(column.getType(), customEnumTypes, customCompositeTypes);
            GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(column.getName())
                    .type(column.isNullable() ? fieldType : new GraphQLNonNull(fieldType))
                    .description("Column " + column.getName());

            typeBuilder.field(fieldBuilder.build());
        }

        // Add fields for forward relationships (foreign keys from this table)
        for (ForeignKeyInfo fk : tableInfo.getForeignKeys()) {
            GraphQLFieldDefinition.Builder fieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                    .name(fk.getReferencedTable().toLowerCase())
                    .type(GraphQLTypeReference.typeRef(fk.getReferencedTable()))
                    .description("Relationship to " + fk.getReferencedTable());

            typeBuilder.field(fieldBuilder.build());
        }
        
        // Add fields for reverse relationships (foreign keys from other tables pointing to this table)
        for (Map.Entry<String, TableInfo> otherEntry : allTables.entrySet()) {
            String otherTableName = otherEntry.getKey();
            TableInfo otherTableInfo = otherEntry.getValue();

            // Skip self-references and views
            if (otherTableName.equals(tableName) || otherTableInfo.isView()) {
                continue;
            }

            // Find foreign keys in other tables that reference this table
            for (ForeignKeyInfo otherFk : otherTableInfo.getForeignKeys()) {
                if (otherFk.getReferencedTable().equalsIgnoreCase(tableName)) {
                    // Create reverse relationship field name (plural)
                    String reverseFieldName = otherTableName.toLowerCase();
                    if (!reverseFieldName.endsWith("s")) {
                        reverseFieldName += "s";
                    }

                    log.info("Adding reverse relationship field '{}' to table '{}' referencing table '{}'", 
                            reverseFieldName, tableName, otherTableName);

                    GraphQLFieldDefinition.Builder reverseFieldBuilder = GraphQLFieldDefinition.newFieldDefinition()
                            .name(reverseFieldName)
                            .type(new GraphQLList(GraphQLTypeReference.typeRef(otherTableName)))
                            .description("Reverse relationship to " + otherTableName);

                    typeBuilder.field(reverseFieldBuilder.build());
                    break; // Only add one reverse relationship per table
                }
            }
        }

        return typeBuilder.build();
    }
    
    /**
     * Creates create input type with custom type support.
     */
    private GraphQLInputObjectType createCreateInputType(String tableName, TableInfo tableInfo,
                                                        Map<String, GraphQLEnumType> customEnumTypes,
                                                        Map<String, GraphQLObjectType> customCompositeTypes) {
        return createInputTypesWithCustomTypes(tableName, tableInfo, false, customEnumTypes, customCompositeTypes);
    }
    
    /**
     * Creates update input type with custom type support.
     */
    private GraphQLInputObjectType createUpdateInputType(String tableName, TableInfo tableInfo,
                                                        Map<String, GraphQLEnumType> customEnumTypes,
                                                        Map<String, GraphQLObjectType> customCompositeTypes) {
        return createInputTypesWithCustomTypes(tableName, tableInfo, true, customEnumTypes, customCompositeTypes);
    }
    
    /**
     * Creates input types (create/update) with custom type support.
     */
    private GraphQLInputObjectType createInputTypesWithCustomTypes(String tableName, TableInfo tableInfo, boolean isUpdate,
                                                                  Map<String, GraphQLEnumType> customEnumTypes,
                                                                  Map<String, GraphQLObjectType> customCompositeTypes) {
        String typeName = tableName + (isUpdate ? "UpdateInput" : "CreateInput");
        GraphQLInputObjectType.Builder inputTypeBuilder = GraphQLInputObjectType.newInputObject()
                .name(typeName)
                .description((isUpdate ? "Update" : "Create") + " input for " + tableName);

        for (ColumnInfo column : tableInfo.getColumns()) {
            // Skip auto-generated columns for create operations
            if (!isUpdate && column.isPrimaryKey()) {
                continue;
            }

            GraphQLInputType fieldType = mapDatabaseTypeToGraphQLInputType(column.getType(), customEnumTypes, customCompositeTypes);
            GraphQLInputObjectField.Builder fieldBuilder = GraphQLInputObjectField.newInputObjectField()
                    .name(column.getName())
                    .type(fieldType)
                    .description("Field " + column.getName());

            inputTypeBuilder.field(fieldBuilder.build());
        }

        return inputTypeBuilder.build();
    }
    
    /**
     * Maps database type to GraphQL input type with custom type support.
     */
    private GraphQLInputType mapDatabaseTypeToGraphQLInputType(String type,
                                                              Map<String, GraphQLEnumType> customEnumTypes,
                                                              Map<String, GraphQLObjectType> customCompositeTypes) {
        // Check for custom enum types first
        if (PostgresTypeOperator.isCustomEnumType(type) && customEnumTypes.containsKey(type)) {
            return customEnumTypes.get(type);
        }

        // For custom composite types, we need to create input versions
        if (PostgresTypeOperator.isCustomCompositeType(type) && customCompositeTypes.containsKey(type)) {
            // For now, treat composite types as String in input (JSON representation)
            // In future, we could create proper input object types for composites
            return GraphQLString;
        }

        // Handle array types
        if (type.contains(ColumnTypeConstant.ARRAY_SUFFIX)) {
            String baseType = type.replace(ColumnTypeConstant.ARRAY_SUFFIX, "");
            GraphQLInputType elementType = mapDatabaseTypeToGraphQLInputType(baseType, customEnumTypes, customCompositeTypes);
            return new GraphQLList(elementType);
        }

        // Fall back to standard input type mapping
        return mapDatabaseTypeToGraphQLInputType(type);
    }

}
