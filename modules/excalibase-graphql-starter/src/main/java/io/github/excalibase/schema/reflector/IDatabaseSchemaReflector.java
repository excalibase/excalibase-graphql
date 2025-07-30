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

import java.util.List;
import java.util.Map;

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
     * Get custom enum types for default schema
     */
    List<CustomEnumInfo> getCustomEnumTypes();

    /**
     * Get custom enum types for specific schema
     */
    List<CustomEnumInfo> getCustomEnumTypes(String schema);

    /**
     * Get custom composite types for default schema
     */
    List<CustomCompositeTypeInfo> getCustomCompositeTypes();

    /**
     * Get custom composite types for specific schema
     */
    List<CustomCompositeTypeInfo> getCustomCompositeTypes(String schema);

    /**
     * Get domain types for default schema
     */
    Map<String, String> getDomainTypeToBaseTypeMap();

    /**
     * Get domain types for specific schema
     */
    Map<String, String> getDomainTypeToBaseTypeMap(String schema);

    /**
     * Get enum values for a specific enum type
     */
    List<String> getEnumValues(String enumName, String schema);

    /**
     * Clear reflection cache
     */
    void clearCache();

    /**
     * Clear reflection cache for specific schema
     */
    void clearCache(String schema);
}
