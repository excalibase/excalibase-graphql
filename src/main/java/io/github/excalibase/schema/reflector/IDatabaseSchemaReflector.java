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
package io.github.excalibase.schema.reflector;

import io.github.excalibase.model.TableInfo;

import java.util.Map;

/**
 * Interface for reflecting database schema metadata.
 * 
 * <p>This interface defines the contract for implementations that introspect
 * database schemas to extract table, column, and relationship information.
 * The reflected metadata is used by GraphQL schema generators to create
 * appropriate GraphQL types and resolvers.</p>
 * 
 * <p>Implementations are responsible for:</p>
 * <ul>
 *   <li>Discovering all tables in the configured schema</li>
 *   <li>Extracting column metadata including names, types, and constraints</li>
 *   <li>Identifying foreign key relationships between tables</li>
 *   <li>Filtering tables based on application configuration</li>
 * </ul>
 * 
 * <p>Implementations should be database-specific and annotated with
 * {@link io.github.excalibase.annotation.ExcalibaseService} for proper service lookup.</p>
 *
 * @see io.github.excalibase.model.TableInfo
 * @see io.github.excalibase.model.ColumnInfo
 * @see io.github.excalibase.model.ForeignKeyInfo
 * @see io.github.excalibase.annotation.ExcalibaseService
 */
public interface IDatabaseSchemaReflector {
    
    /**
     * Reflects the database schema and returns table metadata.
     * 
     * <p>This method connects to the database and extracts comprehensive metadata
     * about all tables within the configured schema. The returned map contains
     * complete information about each table including its columns, data types,
     * constraints, and foreign key relationships.</p>
     * 
     * @return a map where keys are table names and values are TableInfo objects
     *         containing complete metadata for each table
     * @throws RuntimeException if database connection fails or schema reflection
     *                         encounters errors
     * @throws io.github.excalibase.exception.EmptySchemaException if no tables
     *                         are found in the configured schema
     */
    Map<String, TableInfo> reflectSchema();
}
