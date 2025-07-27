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
package io.github.excalibase.schema.generator;

import graphql.schema.GraphQLSchema;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.TableInfo;

import java.util.List;
import java.util.Map;

/**
 * Interface for generating GraphQL schemas from database table metadata.
 * 
 * <p>This interface defines the contract for implementations that convert database
 * table information into a complete GraphQL schema. Implementations are responsible
 * for creating GraphQL types, fields, input types, and ensuring proper type
 * mapping between database and GraphQL types.</p>
 * 
 * <p>The generated schema includes:</p>
 * <ul>
 *   <li>GraphQL object types for each database table</li>
 *   <li>Query fields with pagination support</li>
 *   <li>Mutation types for CRUD operations</li>
 *   <li>Input types for mutations</li>
 *   <li>Connection types for cursor-based pagination</li>
 * </ul>
 * 
 * <p>Implementations should be database-specific and annotated with
 * {@link io.github.excalibase.annotation.ExcalibaseService} for proper service lookup.</p>
 *
 * @see TableInfo
 * @see io.github.excalibase.schema.reflector.IDatabaseSchemaReflector
 * @see io.github.excalibase.annotation.ExcalibaseService
 */
public interface IGraphQLSchemaGenerator {
    
    /**
     * Generates a complete GraphQL schema from database table metadata.
     * 
     * <p>This method takes a map of table information and produces a fully
     * functional GraphQL schema with all necessary types, queries, and mutations.
     * The schema will include appropriate pagination support and proper type
     * mappings for all discovered database tables.</p>
     * 
     * @param tables a map where keys are table names and values are TableInfo objects
     *               containing metadata about each table including columns and foreign keys
     * @return a complete GraphQL schema ready for use with GraphQL execution engines
     * @throws RuntimeException if schema generation fails due to invalid table metadata
     *                         or unsupported database types
     */
    GraphQLSchema generateSchema(Map<String, TableInfo> tables);

    /**
     * Generates a complete GraphQL schema from database table metadata with custom types.
     * 
     * <p>This method extends the basic schema generation to include support for PostgreSQL
     * custom enum and composite types. Custom enum types are converted to GraphQL enum types,
     * and custom composite types are converted to GraphQL object types.</p>
     * 
     * @param tables a map where keys are table names and values are TableInfo objects
     *               containing metadata about each table including columns and foreign keys
     * @param customEnums list of custom PostgreSQL enum types to include in the schema
     * @param customComposites list of custom PostgreSQL composite types to include in the schema
     * @return a complete GraphQL schema ready for use with GraphQL execution engines
     * @throws RuntimeException if schema generation fails due to invalid table metadata
     *                         or unsupported database types
     */
    default GraphQLSchema generateSchema(Map<String, TableInfo> tables, 
                                       List<CustomEnumInfo> customEnums,
                                       List<CustomCompositeTypeInfo> customComposites) {
        // Default implementation just calls the basic method for backward compatibility
        return generateSchema(tables);
    }
}
