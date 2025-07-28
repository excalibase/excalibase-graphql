/*
 * Copyright 2025 Excalibase Team and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.excalibase.config;

import graphql.GraphQL;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import io.github.excalibase.constant.GraphqlConstant;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.postgres.generator.PostgresGraphQLSchemaGeneratorImplement;
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

/**
 * Configuration for dynamically generating GraphQL schema from database metadata.
 */
@Configuration
public class GraphqlConfig {
    private static final Logger log = LoggerFactory.getLogger(GraphqlConfig.class);
    private final AppConfig appConfig;
    private final ServiceLookup serviceLookup;

    public GraphqlConfig(AppConfig appConfig, ServiceLookup serviceLookup) {
        this.appConfig = appConfig;
        this.serviceLookup = serviceLookup;
    }

    /**
     * Creates GraphQL instance with schema and resolvers generated from database tables.
     * Includes support for custom enum and composite types.
     */
    @Bean
    public GraphQL graphQL() {
        log.info("Loading GraphQL for database :{} ", appConfig.getDatabaseType().getName());
        IDatabaseSchemaReflector schemaReflector = getDatabaseSchemaReflector();
        IGraphQLSchemaGenerator schemaGenerator = getGraphQLSchemaGenerator();
        
        // Inject the reflector into the schema generator (for PostgresSQL implementation)
        if (schemaGenerator instanceof PostgresGraphQLSchemaGeneratorImplement) {
            ((PostgresGraphQLSchemaGeneratorImplement) schemaGenerator).setSchemaReflector(schemaReflector);
        }
        
        // Reflect database schema - PostgreSQL implementation will handle custom types internally
        Map<String, TableInfo> tables = schemaReflector.reflectSchema();
        
        // Generate schema - PostgreSQL implementation automatically includes custom types
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

            // Add data fetchers for forward relationships
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

            // Add data fetchers for reverse relationships (other tables referencing this table)
            for (var otherEntry : tables.entrySet()) {
                String otherTableName = otherEntry.getKey();
                var otherTableInfo = otherEntry.getValue();

                // Skip self-references and views
                if (otherTableName.equals(tableName) || otherTableInfo.isView()) {
                    continue;
                }

                // Find foreign keys in other tables that reference this table
                for (var otherFk : otherTableInfo.getForeignKeys()) {
                    if (otherFk.getReferencedTable().equalsIgnoreCase(tableName)) {
                        // Create reverse relationship field name (plural)
                        String reverseFieldName = otherTableName.toLowerCase();
                        if (!reverseFieldName.endsWith("s")) {
                            reverseFieldName += "s";
                        }

                        // Add reverse relationship data fetcher that returns a list
                        codeRegistry.dataFetcher(
                                FieldCoordinates.coordinates(tableName, reverseFieldName),
                                dataFetcher.createReverseRelationshipDataFetcher(
                                        tableName,
                                        otherTableName,
                                        otherFk.getColumnName(),
                                        otherFk.getReferencedColumn()
                                )
                        );
                    }
                }
            }

            String capitalizedTableName = tableName.substring(0, 1).toUpperCase() + tableName.substring(1).toLowerCase();

            // Only add mutations for tables, not views
            if (!tableInfo.isView()) {
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
