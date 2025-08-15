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
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLFieldDefinition;
import static graphql.Scalars.GraphQLString;
import org.springframework.beans.factory.annotation.Qualifier;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.constant.GraphqlConstant;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.model.RolePrivileges;
import io.github.excalibase.postgres.generator.PostgresGraphQLSchemaGeneratorImplement;
import io.github.excalibase.schema.fetcher.IDatabaseDataFetcher;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;
import io.github.excalibase.schema.mutator.IDatabaseMutator;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import io.github.excalibase.schema.subscription.IDatabaseSubscription;
import io.github.excalibase.service.DatabaseRoleService;
import io.github.excalibase.service.FullSchemaService;
import io.github.excalibase.service.IRolePrivilegeService;
import io.github.excalibase.service.SchemaFilterService;
import io.github.excalibase.service.ServiceLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * Configuration for dynamically generating GraphQL schema from database metadata.
 * Supports role-aware schema generation using Root + Filter approach for performance.
 */
@Configuration
public class GraphqlConfig {
    private static final Logger log = LoggerFactory.getLogger(GraphqlConfig.class);
    private final AppConfig appConfig;
    private final ServiceLookup serviceLookup;
    private final DatabaseRoleService databaseRoleService;
    private final FullSchemaService fullSchemaService;
    private final SchemaFilterService schemaFilterService;
    private final Instrumentation securityInstrumentation;
    
    // Cache GraphQL instances per role for performance
    private final TTLCache<String, GraphQL> roleBasedGraphQLCache;

    public GraphqlConfig(AppConfig appConfig, ServiceLookup serviceLookup, DatabaseRoleService databaseRoleService,
                        FullSchemaService fullSchemaService, SchemaFilterService schemaFilterService,
                        @Qualifier("graphqlSecurityInstrumentation") Instrumentation securityInstrumentation) {
        this.appConfig = appConfig;
        this.serviceLookup = serviceLookup;
        this.databaseRoleService = databaseRoleService;
        this.fullSchemaService = fullSchemaService;
        this.schemaFilterService = schemaFilterService;
        this.securityInstrumentation = securityInstrumentation;
        this.roleBasedGraphQLCache = new TTLCache<>(Duration.ofMinutes(30));
    }

    /**
     * Creates GraphQL instance with schema and resolvers generated from database tables.
     * Includes support for custom enum and composite types.
     * Uses default schema (no specific role).
     */
    @Bean
    public GraphQL graphQL() {
        return getGraphQLForRole(null);
    }

    /**
     * Gets GraphQL instance for a specific database role using Root + Filter approach.
     * Much faster than SET ROLE approach: ~20ms vs ~300ms for role-specific schema generation.
     * 
     * @param databaseRole The PostgreSQL role to filter schema for (null for default)
     * @return GraphQL instance with role-aware filtered schema
     */
    public GraphQL getGraphQLForRole(String databaseRole) {
        String cacheKey = databaseRole != null ? databaseRole : "default";
        
        return roleBasedGraphQLCache.computeIfAbsent(cacheKey, key -> {
            log.debug("Generating GraphQL schema for role: {}", databaseRole != null ? databaseRole : "default");
            return buildGraphQLForRole(databaseRole);
        });
    }

    private GraphQL buildGraphQLForRole(String databaseRole) {
        Map<String, TableInfo> fullSchema = fullSchemaService.getFullSchema();
        
        Map<String, TableInfo> filteredSchema;
        
        if (appConfig.getSecurity().isRoleBasedSchema() && 
            databaseRole != null && !databaseRole.trim().isEmpty()) {
            
            IRolePrivilegeService rolePrivilegeService = serviceLookup.forBean(IRolePrivilegeService.class, appConfig.getDatabaseType().getName());
            RolePrivileges rolePrivileges = rolePrivilegeService.getRolePrivileges(databaseRole);
            filteredSchema = schemaFilterService.filterSchemaForRole(fullSchema, rolePrivileges);
            log.debug("Role '{}' has access to {}/{} tables", databaseRole, filteredSchema.size(), fullSchema.size());
        } else {
            filteredSchema = fullSchema;
            if (databaseRole != null && !databaseRole.trim().isEmpty()) {
                log.debug("Role-based schema filtering disabled - role '{}' using full schema: {} tables", 
                         databaseRole, filteredSchema.size());
            } else {
                log.debug("Default role using full schema: {} tables", filteredSchema.size());
            }
        }
                 
        Map<String, TableInfo> tables = filteredSchema;
        
        IGraphQLSchemaGenerator schemaGenerator = getGraphQLSchemaGenerator();
        IDatabaseSchemaReflector schemaReflector = getDatabaseSchemaReflector();
        
        if (schemaGenerator instanceof PostgresGraphQLSchemaGeneratorImplement) {
            ((PostgresGraphQLSchemaGeneratorImplement) schemaGenerator).setSchemaReflector(schemaReflector);
        }
        
        GraphQLSchema schema = schemaGenerator.generateSchema(tables);
        
        IDatabaseDataFetcher dataFetcher = getDatabaseDataFetcher();
        IDatabaseMutator mutationResolver = getDatabaseMutator();
        IDatabaseSubscription subscriptionResolver = getDatabaseSubscription();

        GraphQLCodeRegistry.Builder codeRegistry = GraphQLCodeRegistry.newCodeRegistry();

        // Ensure Subscription type exists with a basic health field if missing
        if (schema.getSubscriptionType() == null) {
            GraphQLObjectType subscriptionType = GraphQLObjectType.newObject()
                    .name(GraphqlConstant.SUBSCRIPTION)
                    .field(GraphQLFieldDefinition.newFieldDefinition()
                            .name(GraphqlConstant.HEALTH)
                            .type(GraphQLString)
                            .build())
                    .build();
            schema = schema.transform(builder -> builder.subscription(subscriptionType));
        }

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
            
            // Add subscription for each table
            codeRegistry.dataFetcher(
                    FieldCoordinates.coordinates(GraphqlConstant.SUBSCRIPTION, tableName.toLowerCase() + "_changes"),
                    subscriptionResolver.createTableSubscriptionResolver(tableName)
            );
        }

        schema = schema.transform(builder -> builder.codeRegistry(codeRegistry.build()));
        
        log.debug("Building GraphQL instance with security instrumentation for role: {}", 
                databaseRole != null ? databaseRole : "default");
        
        return GraphQL.newGraphQL(schema)
                .instrumentation(securityInstrumentation)
                .build();
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

    private IDatabaseSubscription getDatabaseSubscription() {
        return serviceLookup.forBean(IDatabaseSubscription.class, appConfig.getDatabaseType().getName());
    }
}
