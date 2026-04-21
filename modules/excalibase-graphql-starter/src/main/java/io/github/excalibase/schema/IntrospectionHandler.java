package io.github.excalibase.schema;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.scalars.ExtendedScalars;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import io.github.excalibase.schema.introspection.ArrRelInsertFactory;
import io.github.excalibase.schema.introspection.CompositeTypeFactory;
import io.github.excalibase.schema.introspection.CreateInputFactory;
import io.github.excalibase.schema.introspection.EnumTypeFactory;
import io.github.excalibase.schema.introspection.FilterInputCatalog;
import io.github.excalibase.schema.introspection.MutationFieldsAssembler;
import io.github.excalibase.schema.introspection.QueryFieldsAssembler;
import io.github.excalibase.schema.introspection.TableObjectTypeFactory;
import io.github.excalibase.schema.introspection.WhereInputFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static graphql.schema.GraphQLObjectType.newObject;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_MUTATION;
import static io.github.excalibase.schema.GraphqlConstants.TYPE_QUERY;

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
        this.schema = buildSchema(schemaInfo);
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

    private GraphQLSchema buildSchema(SchemaInfo schemaInfo) {
        // Step 1: build enums, shared filter inputs, per-enum filter inputs.
        Map<String, GraphQLEnumType> enumTypes = new EnumTypeFactory().build(schemaInfo);
        FilterInputCatalog.FilterInputs filters = FilterInputCatalog.INPUTS;
        Map<String, GraphQLInputObjectType> enumFilters = FilterInputCatalog.buildEnumFilters(enumTypes);

        // Step 2: per-table where/create/object types. ArrRel is built first so
        // CreateInput can embed the wrapper types; both sides of the create-input
        // ↔ arr-rel cycle are broken via GraphQLTypeReference inside ArrRelInsertFactory.
        Map<String, GraphQLInputObjectType> arrRelTypes = new ArrRelInsertFactory().build(schemaInfo);
        Map<String, GraphQLInputObjectType> whereTypes = buildWhereTypes(schemaInfo, enumTypes, enumFilters, filters);
        Map<String, GraphQLInputObjectType> createInputs = buildCreateInputs(schemaInfo, enumTypes, arrRelTypes);
        Map<String, GraphQLObjectType> tableTypes = buildTableTypes(schemaInfo, enumTypes);

        // Step 3: assemble Query + Mutation root types.
        GraphQLObjectType.Builder queryBuilder = newObject().name(TYPE_QUERY);
        new QueryFieldsAssembler().build(schemaInfo, tableTypes, whereTypes).forEach(queryBuilder::field);
        GraphQLObjectType.Builder mutationBuilder = newObject().name(TYPE_MUTATION);
        new MutationFieldsAssembler().build(schemaInfo, tableTypes, createInputs, whereTypes)
                .forEach(mutationBuilder::field);

        // Step 4: schema assembly with additional types.
        GraphQLObjectType mutationType = mutationBuilder.build();
        GraphQLSchema.Builder schemaBuilder = GraphQLSchema.newSchema().query(queryBuilder.build());
        if (!mutationType.getFieldDefinitions().isEmpty()) {
            schemaBuilder.mutation(mutationType);
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

    private Map<String, GraphQLInputObjectType> buildCreateInputs(SchemaInfo schemaInfo,
                                                                  Map<String, GraphQLEnumType> enumTypes,
                                                                  Map<String, GraphQLInputObjectType> arrRelTypes) {
        Map<String, GraphQLInputObjectType> createInputs = new LinkedHashMap<>();
        CreateInputFactory createFactory = new CreateInputFactory();
        for (String table : schemaInfo.getTableNames()) {
            createInputs.put(table, createFactory.buildFor(table, schemaInfo, enumTypes, arrRelTypes));
        }
        return createInputs;
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
        // Register ArrRelInsertInput types for nested FK insert validation
        arrRelTypes.values().forEach(schemaBuilder::additionalType);
        // Register composite types as GraphQL object types
        new CompositeTypeFactory().build(schemaInfo).forEach(schemaBuilder::additionalType);
    }
}
