package io.github.excalibase.schema.generator;

import graphql.schema.GraphQLSchema;
import io.github.excalibase.model.TableInfo;

import java.util.Map;

public interface IGraphQLSchemaGenerator {
    GraphQLSchema generateSchema(Map<String, TableInfo> tables);
}
