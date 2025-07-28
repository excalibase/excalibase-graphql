package io.github.excalibase.postgres.reflector;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.postgres.constant.PostgresSqlConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CompositeTypeAttribute;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.sql.Array;

@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseSchemaReflectorImplement implements IDatabaseSchemaReflector {
    private final JdbcTemplate jdbcTemplate;
    private final TTLCache<String, Map<String, TableInfo>> schemaCache;
    private final TTLCache<String, List<CustomEnumInfo>> enumCache;
    private final TTLCache<String, List<CustomCompositeTypeInfo>> compositeCache;

    @Value("${app.allowed-schema}")
    private String allowedSchema;
    
    @Value("${app.cache.schema-ttl-minutes:30}")
    private int schemaTtlMinutes;

    public PostgresDatabaseSchemaReflectorImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // Note: schemaTtlMinutes will be injected after construction, so we use default here
        // In a future enhancement, we could use constructor injection or @PostConstruct
        this.schemaCache = new TTLCache<>(Duration.ofMinutes(30)); // Default 30 minutes TTL
        this.enumCache = new TTLCache<>(Duration.ofMinutes(30));
        this.compositeCache = new TTLCache<>(Duration.ofMinutes(30));
    }

    @Override
    public Map<String, TableInfo> reflectSchema() {
        // Check if not exists in cache => query
        return schemaCache.computeIfAbsent(allowedSchema, schema -> {
            Map<String, TableInfo> tables = new HashMap<>();

            // Get tables
            List<String> tableNames = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_TABLE_NAME,
                    String.class,
                    schema
            );

            // Process regular tables
            for (String tableName : tableNames) {
                TableInfo tableInfo = processTable(tableName, schema, false);
                tables.put(tableName, tableInfo);
            }

            // Get views (including materialized views)
            List<Map<String, Object>> viewResults = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_VIEW_NAME,
                    schema, schema  // Pass schema twice for UNION query
            );

            // Process views
            for (Map<String, Object> viewResult : viewResults) {
                String viewName = (String) viewResult.get("name");
                TableInfo viewInfo = processTable(viewName, schema, true);
                tables.put(viewName, viewInfo);
            }

            return tables;
        });
    }

    @Override
    public List<CustomEnumInfo> getCustomEnumTypes() {
        return enumCache.computeIfAbsent(allowedSchema, schema -> {
            List<CustomEnumInfo> enumTypes = new ArrayList<>();
            
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_CUSTOM_ENUM_TYPES,
                    schema
            );
            
            for (Map<String, Object> result : results) {
                CustomEnumInfo enumInfo = new CustomEnumInfo();
                enumInfo.setName((String) result.get("enum_name"));
                enumInfo.setSchema((String) result.get("schema_name"));
                
                // Handle PostgreSQL array result
                Object enumValuesObj = result.get("enum_values");
                List<String> enumValues = new ArrayList<>();
                
                if (enumValuesObj instanceof Array) {
                    try {
                        Array array = (Array) enumValuesObj;
                        String[] values = (String[]) array.getArray();
                        enumValues = List.of(values);
                    } catch (Exception e) {
                        // Log error and continue with empty list
                        enumValues = new ArrayList<>();
                    }
                } else if (enumValuesObj instanceof String) {
                    // Handle single string case
                    String arrayString = (String) enumValuesObj;
                    if (arrayString.startsWith("{") && arrayString.endsWith("}")) {
                        String content = arrayString.substring(1, arrayString.length() - 1);
                        if (!content.isEmpty()) {
                            enumValues = List.of(content.split(","));
                        }
                    }
                }
                
                enumInfo.setValues(enumValues);
                enumTypes.add(enumInfo);
            }
            
            return enumTypes;
        });
    }

    @Override
    public List<CustomEnumInfo> getCustomEnumTypes(String schema) {
        return enumCache.computeIfAbsent(schema, schemaKey -> {
            List<CustomEnumInfo> enumTypes = new ArrayList<>();
            
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_CUSTOM_ENUM_TYPES,
                    schemaKey
            );
            
            for (Map<String, Object> result : results) {
                CustomEnumInfo enumInfo = new CustomEnumInfo();
                enumInfo.setName((String) result.get("enum_name"));
                enumInfo.setSchema((String) result.get("schema_name"));
                
                // Handle PostgreSQL array result
                Object enumValuesObj = result.get("enum_values");
                List<String> enumValues = new ArrayList<>();
                
                if (enumValuesObj instanceof Array) {
                    try {
                        Array array = (Array) enumValuesObj;
                        String[] values = (String[]) array.getArray();
                        enumValues = List.of(values);
                    } catch (Exception e) {
                        // Log error and continue with empty list
                        enumValues = new ArrayList<>();
                    }
                } else if (enumValuesObj instanceof String) {
                    // Handle single string case
                    String arrayString = (String) enumValuesObj;
                    if (arrayString.startsWith("{") && arrayString.endsWith("}")) {
                        String content = arrayString.substring(1, arrayString.length() - 1);
                        if (!content.isEmpty()) {
                            enumValues = List.of(content.split(","));
                        }
                    }
                }
                
                enumInfo.setValues(enumValues);
                enumTypes.add(enumInfo);
            }
            
            return enumTypes;
        });
    }

    @Override
    public List<CustomCompositeTypeInfo> getCustomCompositeTypes() {
        return compositeCache.computeIfAbsent(allowedSchema, schema -> {
            List<CustomCompositeTypeInfo> compositeTypes = new ArrayList<>();
            Map<String, CustomCompositeTypeInfo> typeMap = new HashMap<>();
            
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_CUSTOM_COMPOSITE_TYPES,
                    schema
            );
            
            for (Map<String, Object> result : results) {
                String typeName = (String) result.get("type_name");
                String schemaName = (String) result.get("schema_name");
                
                // Get or create composite type info
                CustomCompositeTypeInfo typeInfo = typeMap.get(typeName);
                if (typeInfo == null) {
                    typeInfo = new CustomCompositeTypeInfo();
                    typeInfo.setName(typeName);
                    typeInfo.setSchema(schemaName);
                    typeMap.put(typeName, typeInfo);
                    compositeTypes.add(typeInfo);
                }
                
                // Add attribute
                CompositeTypeAttribute attribute = new CompositeTypeAttribute();
                attribute.setName((String) result.get("attribute_name"));
                attribute.setType((String) result.get("attribute_type"));
                attribute.setOrder((Integer) result.get("attribute_order"));
                attribute.setNullable("YES".equals(result.get("is_nullable")));
                
                typeInfo.getAttributes().add(attribute);
            }
            
            return compositeTypes;
        });
    }

    @Override
    public List<CustomCompositeTypeInfo> getCustomCompositeTypes(String schema) {
        return compositeCache.computeIfAbsent(schema, schemaKey -> {
            List<CustomCompositeTypeInfo> compositeTypes = new ArrayList<>();
            Map<String, CustomCompositeTypeInfo> typeMap = new HashMap<>();
            
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_CUSTOM_COMPOSITE_TYPES,
                    schemaKey
            );
            
            for (Map<String, Object> result : results) {
                String typeName = (String) result.get("type_name");
                String schemaName = (String) result.get("schema_name");
                
                // Get or create composite type info
                CustomCompositeTypeInfo typeInfo = typeMap.get(typeName);
                if (typeInfo == null) {
                    typeInfo = new CustomCompositeTypeInfo();
                    typeInfo.setName(typeName);
                    typeInfo.setSchema(schemaName);
                    typeMap.put(typeName, typeInfo);
                    compositeTypes.add(typeInfo);
                }
                
                // Add attribute
                CompositeTypeAttribute attribute = new CompositeTypeAttribute();
                attribute.setName((String) result.get("attribute_name"));
                attribute.setType((String) result.get("attribute_type"));
                attribute.setOrder((Integer) result.get("attribute_order"));
                attribute.setNullable("YES".equals(result.get("is_nullable")));
                
                typeInfo.getAttributes().add(attribute);
            }
            
            return compositeTypes;
        });
    }

    @Override
    public List<String> getEnumValues(String enumName, String schema) {
        List<String> values = new ArrayList<>();
        
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                PostgresSqlConstant.GET_ENUM_VALUES_FOR_TYPE,
                enumName,
                schema
        );
        
        for (Map<String, Object> result : results) {
            values.add((String) result.get("value"));
        }
        
        return values;
    }

    /**
     * Processes a table or view and returns TableInfo with metadata.
     * 
     * @param name The name of the table or view
     * @param schema The schema name
     * @param isView Whether this is a view (true) or table (false)
     * @return TableInfo with complete metadata
     */
    private TableInfo processTable(String name, String schema, boolean isView) {
        TableInfo tableInfo = new TableInfo();
        tableInfo.setName(name);
        tableInfo.setView(isView);

        // Get columns - use view-specific query for views, regular query for tables
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                isView ? PostgresSqlConstant.GET_VIEW_COLUMNS : PostgresSqlConstant.GET_COLUMNS,
                name, schema
        );

        for (Map<String, Object> column : columns) {
            ColumnInfo columnInfo = new ColumnInfo();
            columnInfo.setName((String) column.get("column_name"));
            columnInfo.setType((String) column.get("data_type"));
            columnInfo.setNullable("YES".equals(column.get("is_nullable")));
            tableInfo.getColumns().add(columnInfo);
        }

        // Only process primary keys and foreign keys for tables, not views
        if (!isView) {
            // Get primary keys using pg_catalog
            List<String> primaryKeys = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_PRIMARY_KEYS,
                    String.class,
                    name, schema
            );

            for (ColumnInfo column : tableInfo.getColumns()) {
                column.setPrimaryKey(primaryKeys.contains(column.getName()));
            }

            // Get foreign keys using pg_catalog
            List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_FOREIGN_KEYS,
                    name, schema
            );

            for (Map<String, Object> fk : foreignKeys) {
                ForeignKeyInfo fkInfo = new ForeignKeyInfo();
                fkInfo.setColumnName((String) fk.get("column_name"));
                fkInfo.setReferencedTable((String) fk.get("foreign_table_name"));
                fkInfo.setReferencedColumn((String) fk.get("foreign_column_name"));
                tableInfo.getForeignKeys().add(fkInfo);
            }
        }

        return tableInfo;
    }

    @Override
    public void clearCache() {
        schemaCache.clear();
        enumCache.clear();
        compositeCache.clear();
    }

    @Override
    public void clearCache(String schema) {
        schemaCache.remove(schema);
        enumCache.remove(schema);
        compositeCache.remove(schema);
    }
    
    /**
     * Gets cache statistics for monitoring purposes.
     * 
     * @return cache statistics as a string
     */
    public String getCacheStats() {
        return "Schema: " + schemaCache.getStats() + 
               ", Enum: " + enumCache.getStats() + 
               ", Composite: " + compositeCache.getStats();
    }
    
    /**
     * Cleanup method called when the bean is destroyed.
     * Ensures proper shutdown of the cache's background threads.
     */
    @PreDestroy
    public void destroy() {
        schemaCache.shutdown();
        enumCache.shutdown();
        compositeCache.shutdown();
    }
}
