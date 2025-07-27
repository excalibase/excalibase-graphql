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
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.CustomCompositeTypeInfo;

import java.util.Map;
import java.util.List;

/**
 * Interface for database schema reflection.
 * Implementations should provide methods to extract table structure, relationships,
 * and custom types from the database.
 */
public interface IDatabaseSchemaReflector {

    /**
     * Reflects the entire database schema and returns a map of table information.
     * This method should cache results for performance.
     *
     * @return A map where keys are table names and values are TableInfo objects
     */
    Map<String, TableInfo> reflectSchema();

    /**
     * Reflects and returns information about custom enum types in the schema.
     * This includes user-defined enum types with their possible values.
     *
     * @return A list of CustomEnumInfo objects representing all custom enum types
     */
    List<CustomEnumInfo> getCustomEnumTypes();

    /**
     * Reflects and returns information about custom enum types in the specified schema.
     * This includes user-defined enum types with their possible values.
     *
     * @param schema The schema name to search for custom enum types
     * @return A list of CustomEnumInfo objects representing all custom enum types in the schema
     */
    List<CustomEnumInfo> getCustomEnumTypes(String schema);

    /**
     * Reflects and returns information about custom composite types in the schema.
     * This includes user-defined composite (object) types with their attributes.
     *
     * @return A list of CustomCompositeTypeInfo objects representing all custom composite types
     */
    List<CustomCompositeTypeInfo> getCustomCompositeTypes();

    /**
     * Reflects and returns information about custom composite types in the specified schema.
     * This includes user-defined composite (object) types with their attributes.
     *
     * @param schema The schema name to search for custom composite types
     * @return A list of CustomCompositeTypeInfo objects representing all custom composite types in the schema
     */
    List<CustomCompositeTypeInfo> getCustomCompositeTypes(String schema);

    /**
     * Gets the values for a specific enum type.
     *
     * @param enumName The name of the enum type
     * @param schema The schema name where the enum is defined
     * @return A list of string values for the enum type
     */
    List<String> getEnumValues(String enumName, String schema);

    /**
     * Clears all cached schema information.
     */
    void clearCache();

    /**
     * Clears cached schema information for a specific schema.
     *
     * @param schema The schema name to clear from cache
     */
    void clearCache(String schema);
}
