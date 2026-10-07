package io.github.excalibase.schema;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.github.excalibase.schema.introspection.CompositeTypeFactory;
import io.github.excalibase.schema.introspection.CreateInputFactory;
import io.github.excalibase.schema.introspection.EnumTypeFactory;
import io.github.excalibase.schema.introspection.FunctionFieldsAssembler;
import io.github.excalibase.schema.introspection.FilterInputCatalog;
import io.github.excalibase.schema.introspection.MutationFieldsAssembler;
import io.github.excalibase.schema.introspection.QueryFieldsAssembler;
import io.github.excalibase.schema.introspection.RelationshipInsertFactory;
import io.github.excalibase.schema.introspection.SubscriptionFieldsAssembler;
import io.github.excalibase.schema.introspection.TableObjectTypeFactory;
import io.github.excalibase.schema.introspection.WhereInputFactory;

import io.github.excalibase.security.RlsOp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.schema.GraphQLObjectType.newObject;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_MUTATION;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_QUERY;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_SUBSCRIPTION;

/**
 * Builds a GraphQL-Java schema from SchemaInfo metadata for introspection queries only.
 * No resolvers — only used for __schema / __type queries.
 *
 * <p>This class is a thin coordinator: it threads the output of each
 * introspection factory (enums → filters → where/create/table types →
 * query/mutation fields) into the next, then assembles the final
 * {@link GraphQLSchema}. Each build step lives in its own factory class
 * under {@code io.github.excalibase.schema.introspection}.
 */
public class IntrospectionHandler {

    private final GraphQL graphQL;
    private final GraphQLSchema schema;

    public IntrospectionHandler(SchemaInfo schemaInfo) {
        this(schemaInfo, TableAccess.UNRESTRICTED);
    }

    /**
     * {@code schemaInfo} is the caller's view of the database, so what they may not
     * read is already missing from it; {@code access} adds what they may do with the
     * remaining tables.
     */
    public IntrospectionHandler(SchemaInfo schemaInfo, TableAccess access) {
        this(schemaInfo, access, ExposedFunctions.NONE);
    }

    /** As above, plus a root field per tracked function the caller may call. */
    public IntrospectionHandler(SchemaInfo schemaInfo, TableAccess access, ExposedFunctions functions) {
        this(schemaInfo, access, functions, true);
    }

    /** As above; {@code nestedInserts} is false for a store whose mutation compiler cannot nest inserts. */
    public IntrospectionHandler(SchemaInfo schemaInfo, TableAccess access, ExposedFunctions functions,
                                boolean nestedInserts) {
        this.schema = buildSchema(schemaInfo, access, functions == null ? ExposedFunctions.NONE : functions,
                nestedInserts);
        this.graphQL = GraphQL.newGraphQL(this.schema).build();
    }

    /** Expose the schema for external validation (e.g. graphql-java Validator). */
    public GraphQLSchema getSchema() { return schema; }

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

    private GraphQLSchema buildSchema(SchemaInfo schemaInfo, TableAccess access, ExposedFunctions functions,
                                      boolean nestedInserts) {
        // Step 1: build enums, shared filter inputs, per-enum filter inputs.
        Map<String, GraphQLEnumType> enumTypes = new EnumTypeFactory().build(schemaInfo);
        FilterInputCatalog.FilterInputs filters = FilterInputCatalog.INPUTS;
        Map<String, GraphQLInputObjectType> enumFilters = FilterInputCatalog.buildEnumFilters(enumTypes);

        // Step 2: per-table where/create/object types. The nested-insert wrappers are built first so
        // CreateInput can embed them; the create-input ↔ wrapper cycle is broken by type references.
        Map<String, GraphQLInputObjectType> arrRelTypes = new RelationshipInsertFactory().build(schemaInfo,
                nestedInserts);
        Map<String, GraphQLInputObjectType> whereTypes = buildWhereTypes(schemaInfo, enumTypes, enumFilters, filters);
        MutationFieldsAssembler.Inputs inputs = new MutationFieldsAssembler.Inputs(
                buildInputs(schemaInfo, access, enumTypes, arrRelTypes, RlsOp.INSERT),
                buildInputs(schemaInfo, access, enumTypes, arrRelTypes, RlsOp.UPDATE), whereTypes);
        Map<String, GraphQLObjectType> tableTypes = buildTableTypes(schemaInfo, enumTypes);

        // Step 3: assemble Query + Mutation root types.
        GraphQLObjectType.Builder queryBuilder = newObject().name(TYPE_QUERY);
        new QueryFieldsAssembler().build(schemaInfo, tableTypes, whereTypes, access).forEach(queryBuilder::field);
        FunctionFieldsAssembler functionFields = new FunctionFieldsAssembler();
        functionFields.build(functions, ExposedFunction.Operation.QUERY, tableTypes, whereTypes)
                .forEach(queryBuilder::field);
        GraphQLObjectType.Builder mutationBuilder = newObject().name(TYPE_MUTATION);
        new MutationFieldsAssembler().build(schemaInfo, tableTypes, inputs, access)
                .forEach(mutationBuilder::field);
        functionFields.build(functions, ExposedFunction.Operation.MUTATION, tableTypes, whereTypes)
                .forEach(mutationBuilder::field);

        // Step 4: schema assembly with additional types.
        GraphQLObjectType mutationType = mutationBuilder.build();
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema().query(queryBuilder.build());
        if (!mutationType.getFieldDefinitions().isEmpty()) {
            schemaBuilder.mutation(mutationType);
        }
        List<GraphQLFieldDefinition> subscriptions = new SubscriptionFieldsAssembler().build(schemaInfo);
        if (!subscriptions.isEmpty()) {
            schemaBuilder.subscription(newObject().name(TYPE_SUBSCRIPTION).fields(subscriptions).build());
        }
        registerAdditionalTypes(schemaBuilder, schemaInfo, enumTypes, arrRelTypes);
        return schemaBuilder.build();
    }

    private Map<String, GraphQLInputObjectType> buildWhereTypes(SchemaInfo schemaInfo,
                                                                Map<String, GraphQLEnumType> enumTypes,
                                                                Map<String, GraphQLInputObjectType> enumFilters,
                                                                FilterInputCatalog.FilterInputs filters) {
        Map<String, GraphQLInputObjectType> whereTypes = new LinkedHashMap<>();
        WhereInputFactory whereFactory = new WhereInputFactory();
        for (String table : schemaInfo.getTableNames()) {
            whereTypes.put(table, whereFactory.buildFor(table, schemaInfo, enumTypes, enumFilters, filters));
        }
        return whereTypes;
    }

    /** Insert or update inputs for the tables the caller may write, holding only the columns it may set. */
    private Map<String, GraphQLInputObjectType> buildInputs(SchemaInfo schemaInfo, TableAccess access,
                                                            Map<String, GraphQLEnumType> enumTypes,
                                                            Map<String, GraphQLInputObjectType> arrRelTypes,
                                                            RlsOp operation) {
        Map<String, GraphQLInputObjectType> inputs = new LinkedHashMap<>();
        CreateInputFactory factory = new CreateInputFactory();
        for (String table : schemaInfo.getMutableTableNames()) {
            if (schemaInfo.isView(table) || !access.permits(table, operation)) continue;
            var columns = access.settableColumns(table, operation, schemaInfo);
            inputs.put(table, operation == RlsOp.INSERT
                    ? factory.buildFor(table, columns, schemaInfo, enumTypes, arrRelTypes)
                    : factory.buildUpdateFor(table, columns, schemaInfo, enumTypes));
        }
        return inputs;
    }

    private Map<String, GraphQLObjectType> buildTableTypes(SchemaInfo schemaInfo,
                                                           Map<String, GraphQLEnumType> enumTypes) {
        Map<String, GraphQLObjectType> tableTypes = new LinkedHashMap<>();
        TableObjectTypeFactory tableFactory = new TableObjectTypeFactory();
        for (String table : schemaInfo.getTableNames()) {
            tableTypes.put(table, tableFactory.buildFor(table, schemaInfo, enumTypes));
        }
        return tableTypes;
    }

    private void registerAdditionalTypes(GraphQLSchema.Builder schemaBuilder,
                                         SchemaInfo schemaInfo,
                                         Map<String, GraphQLEnumType> enumTypes,
                                         Map<String, GraphQLInputObjectType> arrRelTypes) {
        // Register extended scalar types
        schemaBuilder.additionalType(ExtendedScalars.GraphQLBigInteger);
        // Register enum types as additional types so they're discoverable via __type
        enumTypes.values().forEach(schemaBuilder::additionalType);
        // Register the nested-insert wrapper types
        arrRelTypes.values().forEach(schemaBuilder::additionalType);
        // Register composite types as GraphQL object types
        new CompositeTypeFactory().build(schemaInfo).forEach(schemaBuilder::additionalType);
    }
}
