package io.github.excalibase.config;

import graphql.GraphQL;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import io.github.excalibase.constant.GraphqlConstant;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.fetcher.IDatabaseDataFetcher;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;
import io.github.excalibase.schema.mutator.IDatabaseMutator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class GraphqlConfig {
    private static final Logger log = LoggerFactory.getLogger(GraphqlConfig.class);
    private final AppConfig appConfig;
    private final ServiceLookup serviceLookup;

    public GraphqlConfig(AppConfig appConfig, ServiceLookup serviceLookup) {
        this.appConfig = appConfig;
        this.serviceLookup = serviceLookup;
    }

    @Bean
    public GraphQL graphQL() {
        log.info("Loading GraphQL for database :{} ", appConfig.getDatabaseType().getName());
        IDatabaseSchemaReflector schemaReflector = getDatabaseSchemaReflector();
        IGraphQLSchemaGenerator schemaGenerator = getGraphQLSchemaGenerator();
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        GraphQLSchema schema = schemaGenerator.generateSchema(tables);
        IDatabaseDataFetcher dataFetcher = getDatabaseDataFetcher();
        IDatabaseMutator mutationResolver = getDatabaseMutator();

        GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();

        for (var entry : tables.entrySet()) {
            String tableName = entry.getKey();
            var tableInfo = entry.getValue();

            // Add data fetcher for the table query with offset-based pagination
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates(GraphqlConstant.QUERY, tableName.toLowerCase()),
                    dataFetcher.createTableDataFetcher(tableName)
            );

            // Add data fetcher for the connection (cursor-based pagination && offset-based pagination)
            // This follows the Relay Connection Specification
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates(GraphqlConstant.QUERY, tableName.toLowerCase() + GraphqlConstant.CONNECTION_SUFFIX),
                    dataFetcher.createConnectionDataFetcher(tableName)
            );

            // Add data fetchers for relationships
            for (var fk : tableInfo.getForeignKeys()) {
                codeRegistry.dataFetcher(
                        FieldCoordinates.coordinates(tableName, fk.getReferencedTable().toLowerCase()),
                        dataFetcher.createRelationshipDataFetcher(
                                tableName,
                                fk.getColumnName(),
                                fk.getReferencedTable(),
                                fk.getReferencedColumn()
                        )
                );
            }

            String capitalizedTableName = tableName.substring(0, 1).toUpperCase() + tableName.substring(1).toLowerCase();

            // Create mutation
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates("Mutation", "create" + capitalizedTableName),
                    mutationResolver.createCreateMutationResolver(tableName)
            );

            // Update mutation
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates("Mutation", "update" + capitalizedTableName),
                    mutationResolver.createUpdateMutationResolver(tableName)
            );

            // Delete mutation
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates("Mutation", "delete" + capitalizedTableName),
                    mutationResolver.createDeleteMutationResolver(tableName)
            );

            // Bulk create mutation
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates("Mutation", "createMany" + capitalizedTableName + "s"),
                    mutationResolver.createBulkCreateMutationResolver(tableName)
            );

            // Create with relationships mutation
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates("Mutation", "create" + capitalizedTableName + "WithRelations"),
                    mutationResolver.createCreateWithRelationshipsMutationResolver(tableName)
            );
        }

        schema = schema.transform(builder -> builder.codeRegistry(codeRegistry.build()));
        return GraphQL.newGraphQL(schema).build();
    }

    private IGraphQLSchemaGenerator getGraphQLSchemaGenerator() {
        return serviceLookup.forBean(IGraphQLSchemaGenerator.class, appConfig.getDatabaseType().getName());
    }

    private IDatabaseSchemaReflector getDatabaseSchemaReflector() {
        return serviceLookup.forBean(IDatabaseSchemaReflector.class, appConfig.getDatabaseType().getName());
    }

    private IDatabaseMutator getDatabaseMutator() {
        return serviceLookup.forBean(IDatabaseMutator.class, appConfig.getDatabaseType().getName());
    }

    private IDatabaseDataFetcher getDatabaseDataFetcher() {
        return serviceLookup.forBean(IDatabaseDataFetcher.class, appConfig.getDatabaseType().getName());
    }
}
