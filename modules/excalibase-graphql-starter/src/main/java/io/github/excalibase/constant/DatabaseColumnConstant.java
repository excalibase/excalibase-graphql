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
package io.github.excalibase.constant;

/**
 * Constants for database result set column names used in schema reflection.
 */
public class DatabaseColumnConstant {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private DatabaseColumnConstant() {
    }

    // PostgreSQL system catalog column names
    
    /** Column name for enum type name */
    public static final String ENUM_NAME = "enum_name";
    
    /** Column name for schema name */
    public static final String SCHEMA_NAME = "schema_name";
    
    /** Column name for type name */
    public static final String TYPE_NAME = "type_name";
    
    /** Column name for attribute name */
    public static final String ATTRIBUTE_NAME = "attribute_name";
    
    /** Column name for table column name */
    public static final String COLUMN_NAME = "column_name";
    
    /** Column name for foreign table name */
    public static final String FOREIGN_TABLE_NAME = "foreign_table_name";
    
    /** Column name for foreign column name */
    public static final String FOREIGN_COLUMN_NAME = "foreign_column_name";
    
    /** Column name for data type */
    public static final String DATA_TYPE = "data_type";
    
    /** Column name for nullable indicator */
    public static final String IS_NULLABLE = "is_nullable";
    
    /** Column name for column default value */
    public static final String COLUMN_DEFAULT = "column_default";
    
    /** Column name for attribute type */
    public static final String ATTRIBUTE_TYPE = "attribute_type";
    
    /** Column name for attribute order */
    public static final String ATTRIBUTE_ORDER = "attribute_order";
    
    /** Column name for enum values array */
    public static final String ENUM_VALUES = "enum_values";
    
    /** Column name for enum value */
    public static final String VALUE = "value";
    
    /** Column name for domain name */
    public static final String DOMAIN_NAME = "domain_name";
    
    /** Column name for base type */
    public static final String BASE_TYPE = "base_type";
    
    /** Column name for view/table name */
    public static final String NAME = "name";
    
    /** Column name for object type (view, materialized_view, etc.) */
    public static final String TYPE = "type";
    
    // Common database values
    
    /** Value indicating nullable column */
    public static final String YES = "YES";
    
    /** Value indicating non-nullable column */
    public static final String NO = "NO";
    
    /** View type indicator */
    public static final String VIEW_TYPE = "view";
    
    /** Materialized view type indicator */
    public static final String MATERIALIZED_VIEW_TYPE = "materialized_view";
    
    // PostgreSQL constraint types
    
    /** Primary key constraint type */
    public static final String PRIMARY_KEY_CONSTRAINT = "p";
    
    /** Foreign key constraint type */
    public static final String FOREIGN_KEY_CONSTRAINT = "f";
    
    /** Check constraint type */
    public static final String CHECK_CONSTRAINT = "c";
    
    /** Unique constraint type */
    public static final String UNIQUE_CONSTRAINT = "u";
    
    // PostgreSQL type categories
    
    /** Enum type category */
    public static final String ENUM_TYPE_CATEGORY = "E";
    
    /** Composite type indicator */
    public static final String COMPOSITE_TYPE = "c";
    
    /** Domain type indicator */
    public static final String DOMAIN_TYPE = "d";
} 