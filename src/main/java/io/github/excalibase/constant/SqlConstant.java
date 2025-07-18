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
 * SQL query constants for database schema reflection.
 */
public class SqlConstant {
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
    
    public static final String GET_COLUMNS = """
            SELECT a.attname as column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                   CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END as is_nullable,
                   pg_catalog.pg_get_expr(d.adbin, d.adrelid) as column_default
            FROM pg_catalog.pg_attribute a
            LEFT JOIN pg_catalog.pg_attrdef d ON (a.attrelid = d.adrelid AND a.attnum = d.adnum)
            JOIN pg_catalog.pg_class c ON (a.attrelid = c.oid)
            JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid)
            WHERE c.relname = ?
              AND n.nspname = ?
              AND a.attnum > 0
              AND NOT a.attisdropped
            """;
    
    public static final String GET_VIEW_COLUMNS = """
            SELECT a.attname as column_name,
                   pg_catalog.format_type(a.atttypid, a.atttypmod) as data_type,
                   CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END as is_nullable
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON (a.attrelid = c.oid)
            JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid)
            WHERE c.relname = ?
              AND n.nspname = ?
              AND a.attnum > 0
              AND NOT a.attisdropped
              AND (c.relkind = 'v' OR c.relkind = 'm')
            """;
    
    public static final String GET_PRIMARY_KEYS = """
            SELECT a.attname as column_name
            FROM pg_catalog.pg_constraint c
            JOIN pg_catalog.pg_class rel ON rel.oid = c.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = rel.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
            WHERE c.contype = 'p'
              AND rel.relname = ?
              AND n.nspname = ?
            """;
    
    public static final String GET_FOREIGN_KEYS = """
            SELECT a.attname as column_name,
                   cl2.relname as foreign_table_name,
                   a2.attname as foreign_column_name
            FROM pg_catalog.pg_constraint c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.connamespace
            JOIN pg_catalog.pg_class cl ON cl.oid = c.conrelid
            JOIN pg_catalog.pg_class cl2 ON cl2.oid = c.confrelid
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
            JOIN pg_catalog.pg_attribute a2 ON a2.attrelid = c.confrelid AND a2.attnum = c.confkey[1]
            WHERE c.contype = 'f'
              AND cl.relname = ?
              AND n.nspname = ?
            """;
}
