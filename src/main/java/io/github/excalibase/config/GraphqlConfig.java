package io.github.excalibase.config;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.generator.IGraphQLSchemaGenerator;
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
        return null;
    }

    private IGraphQLSchemaGenerator getGraphQLSchemaGenerator() {
        return serviceLookup.forBean(IGraphQLSchemaGenerator.class, appConfig.getDatabaseType().getName());
    }

    private IDatabaseSchemaReflector getDatabaseSchemaReflector() {
        return serviceLookup.forBean(IDatabaseSchemaReflector.class, appConfig.getDatabaseType().getName());
    }
}
