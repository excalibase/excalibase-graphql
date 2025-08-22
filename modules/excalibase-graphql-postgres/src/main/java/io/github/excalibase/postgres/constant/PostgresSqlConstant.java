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
package io.github.excalibase.postgres.constant;

/**
 * SQL query constants for database schema reflection.
 */
public class PostgresSqlConstant {
    public static final String GET_TABLE_NAME = """
            SELECT tablename 
            FROM pg_catalog.pg_tables 
            WHERE schemaname = ?
            """;
    
    public static final String GET_VIEW_NAME = """
            SELECT viewname as name, 'view' as type
            FROM pg_catalog.pg_views
            WHERE schemaname = ?
            UNION ALL
            SELECT matviewname as name, 'materialized_view' as type
            FROM pg_catalog.pg_matviews
            WHERE schemaname = ?
            """;
    


    // Custom PostgreSQL type queries
    
    public static final String GET_CUSTOM_ENUM_TYPES = """
            SELECT t.typname as enum_name,
                   n.nspname as schema_name,
                   array_agg(e.enumlabel ORDER BY e.enumsortorder) as enum_values
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
            WHERE n.nspname = ?
              AND t.typcategory = 'E'
            GROUP BY t.typname, n.nspname
            ORDER BY t.typname
            """;
    
    public static final String GET_CUSTOM_COMPOSITE_TYPES = """
            SELECT t.typname as type_name,
                   n.nspname as schema_name,
                   a.attname as attribute_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) as attribute_type,
                   a.attnum as attribute_order,
                   CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END as is_nullable
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = t.typrelid
            WHERE n.nspname = ?
              AND t.typtype = 'c'
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY t.typname, a.attnum
            """;
    
    public static final String GET_ENUM_VALUES_FOR_TYPE = """
            SELECT e.enumlabel as value
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            JOIN pg_catalog.pg_enum e ON e.enumtypid = t.oid
            WHERE t.typname = ?
              AND n.nspname = ?
            ORDER BY e.enumsortorder
            """;
    
    public static final String GET_DOMAIN_TYPES = """
            SELECT t.typname as domain_name,
                   n.nspname as schema_name,
                   pg_catalog.format_type(t.typbasetype, t.typtypmod) as base_type,
                   t.typtypmod as type_modifier,
                   t.typndims as array_dimensions,
                   t.typnotnull as not_null,
                   t.typdefault as default_value,
                   c.collname as collation
            FROM pg_catalog.pg_type t
            JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_collation c ON c.oid = t.typcollation
            WHERE n.nspname = ?
              AND t.typtype = 'd'
            ORDER BY t.typname
            """;

    public static final String GET_ALL_COLUMNS = """
            SELECT c.relname as table_name,
                   a.attname as column_name,
                   CASE 
                       WHEN t.typtype = 'd' THEN t.typname
                       ELSE pg_catalog.format_type(a.atttypid, a.atttypmod)
                   END as data_type,
                   CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END as is_nullable,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid) as column_default,
                   t.typtype as type_category,
                   t.typname as base_type_name
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON (a.attrelid = d.adrelid AND a.attnum = d.adnum)
            LEFT JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid)
            JOIN pg_catalog.pg_class c ON (a.attrelid = c.oid)
            JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid)
            WHERE n.nspname = ?
              AND c.relname = ANY(?)
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY c.relname, a.attnum
            """;
    
    public static final String GET_ALL_VIEW_COLUMNS = """
            SELECT c.relname as table_name,
                   a.attname as column_name,
                   CASE 
                       WHEN t.typtype = 'd' THEN t.typname
                       ELSE pg_catalog.format_type(a.atttypid, a.atttypmod)
                   END as data_type,
                   CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END as is_nullable
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid)
            JOIN pg_catalog.pg_class c ON (a.attrelid = c.oid)
            JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid)
            WHERE n.nspname = ?
              AND c.relname = ANY(?)
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND (c.relkind = 'v' OR c.relkind = 'm')
            ORDER BY c.relname, a.attnum
            """;
    
    public static final String GET_ALL_PRIMARY_KEYS = """
            SELECT rel.relname as table_name,
                   a.attname as column_name
            FROM pg_catalog.pg_constraint c
            JOIN pg_catalog.pg_class rel ON rel.oid = c.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = rel.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE c.contype = 'p'
              AND n.nspname = ?
              AND rel.relname = ANY(?)
            ORDER BY rel.relname, a.attname
            """;
    
    public static final String GET_ALL_FOREIGN_KEYS = """
            SELECT cl.relname as table_name,
                   a.attname as column_name,
                   cl2.relname as foreign_table_name,
                   a2.attname as foreign_column_name
            FROM pg_catalog.pg_constraint c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.connamespace
            JOIN pg_catalog.pg_class cl ON cl.oid = c.conrelid
            JOIN pg_catalog.pg_class cl2 ON cl2.oid = c.confrelid
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            JOIN pg_catalog.pg_attribute a2 ON a2.attrelid = c.confrelid AND a2.attnum = c.confkey[1]
            WHERE c.contype = 'f'
              AND n.nspname = ?
              AND cl.relname = ANY(?)
            ORDER BY cl.relname, a.attname
            """;
}
