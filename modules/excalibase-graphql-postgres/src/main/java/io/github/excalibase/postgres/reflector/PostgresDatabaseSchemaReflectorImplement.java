package io.github.excalibase.postgres.reflector;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.postgres.constant.PostgresSqlConstant;
import io.github.excalibase.constant.DatabaseColumnConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CompositeTypeAttribute;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(PostgresDatabaseSchemaReflectorImplement.class);
    private final JdbcTemplate jdbcTemplate;
    private final TTLCache<String, Map<String, TableInfo>> schemaCache;
    private final TTLCache<String, List<CustomEnumInfo>> enumCache;
    private final TTLCache<String, List<CustomCompositeTypeInfo>> compositeCache;
    private final TTLCache<String, Map<String, String>> domainTypeToBaseTypeCache;

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
        this.domainTypeToBaseTypeCache = new TTLCache<>(Duration.ofMinutes(30));
    }

    @Override
    public Map<String, TableInfo> reflectSchema() {
        // Check if not exists in cache => query
        return schemaCache.computeIfAbsent(allowedSchema, schema -> {
            return reflectSchemaOptimized(schema);
        });
    }

    /**
     * Optimized schema reflection that uses bulk queries instead of N+1 queries.
     * This significantly improves performance when there are many tables.
     */
    private Map<String, TableInfo> reflectSchemaOptimized(String schema) {
        Map<String, TableInfo> tables = new HashMap<>();

        // Step 1: Get all table names
        List<String> tableNames = jdbcTemplate.queryForList(
                PostgresSqlConstant.GET_TABLE_NAME,
                String.class,
                schema
        );

        // Step 2: Get all view names (including materialized views)
        List<Map<String, Object>> viewResults = jdbcTemplate.queryForList(
                PostgresSqlConstant.GET_VIEW_NAME,
                schema, schema  // Pass schema twice for UNION query
        );
        
        List<String> viewNames = viewResults.stream()
                .map(result -> (String) result.get(DatabaseColumnConstant.NAME))
                .toList();

        // Step 3: Initialize TableInfo objects for all tables and views
        for (String tableName : tableNames) {
            TableInfo tableInfo = new TableInfo();
            tableInfo.setName(tableName);
            tableInfo.setView(false);
            tables.put(tableName, tableInfo);
        }

        for (String viewName : viewNames) {
            TableInfo viewInfo = new TableInfo();
            viewInfo.setName(viewName);
            viewInfo.setView(true);
            tables.put(viewName, viewInfo);
        }

        // Early return if no tables/views found
        if (tables.isEmpty()) {
            return tables;
        }

        // Step 4: Get domain type mapping (needed for column type resolution)
        Map<String, String> domainTypeToBaseTypeMap = getDomainTypeToBaseTypeMap();

        // Step 5: Bulk fetch all columns for regular tables
        if (!tableNames.isEmpty()) {
            String[] tableNamesArray = tableNames.toArray(new String[0]);
            List<Map<String, Object>> allColumns = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_ALL_COLUMNS,
                    schema, tableNamesArray
            );
            
            processColumnsFromBulkResult(allColumns, tables, domainTypeToBaseTypeMap);
        }

        // Step 6: Bulk fetch all columns for views
        if (!viewNames.isEmpty()) {
            String[] viewNamesArray = viewNames.toArray(new String[0]);
            List<Map<String, Object>> allViewColumns = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_ALL_VIEW_COLUMNS,
                    schema, viewNamesArray
            );
            
            processColumnsFromBulkResult(allViewColumns, tables, domainTypeToBaseTypeMap);
        }

        // Step 7: Bulk fetch primary keys for tables only (not views)
        if (!tableNames.isEmpty()) {
            String[] tableNamesArray = tableNames.toArray(new String[0]);
            List<Map<String, Object>> allPrimaryKeys = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_ALL_PRIMARY_KEYS,
                    schema, tableNamesArray
            );
            
            processPrimaryKeysFromBulkResult(allPrimaryKeys, tables);
        }

        // Step 8: Bulk fetch foreign keys for tables only (not views)
        if (!tableNames.isEmpty()) {
            String[] tableNamesArray = tableNames.toArray(new String[0]);
            List<Map<String, Object>> allForeignKeys = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_ALL_FOREIGN_KEYS,
                    schema, tableNamesArray
            );
            
            processForeignKeysFromBulkResult(allForeignKeys, tables);
        }

        return tables;
    }

    /**
     * Processes column data from bulk query results and populates TableInfo objects.
     */
    private void processColumnsFromBulkResult(List<Map<String, Object>> columnResults, 
                                             Map<String, TableInfo> tables, 
                                             Map<String, String> domainTypeToBaseTypeMap) {
        for (Map<String, Object> columnData : columnResults) {
            String tableName = (String) columnData.get("table_name");
            TableInfo tableInfo = tables.get(tableName);
            
            if (tableInfo != null) {
                ColumnInfo columnInfo = new ColumnInfo();
                columnInfo.setName((String) columnData.get("column_name"));
                
                String dataType = (String) columnData.get("data_type");
                
                // Check if domain type then set base type
                if (domainTypeToBaseTypeMap.containsKey(dataType)) {
                    String baseType = domainTypeToBaseTypeMap.get(dataType);
                    columnInfo.setType(baseType);
                } else {
                    columnInfo.setType(dataType);
                }
                
                columnInfo.setNullable(DatabaseColumnConstant.YES.equals(columnData.get(DatabaseColumnConstant.IS_NULLABLE)));
                tableInfo.getColumns().add(columnInfo);
            }
        }
    }

    /**
     * Processes primary key data from bulk query results and updates TableInfo objects.
     */
    private void processPrimaryKeysFromBulkResult(List<Map<String, Object>> primaryKeyResults, 
                                                 Map<String, TableInfo> tables) {
        // Group primary keys by table name
        Map<String, List<String>> primaryKeysByTable = new HashMap<>();
        
        for (Map<String, Object> pkData : primaryKeyResults) {
            String tableName = (String) pkData.get("table_name");
            String columnName = (String) pkData.get("column_name");
            
            primaryKeysByTable.computeIfAbsent(tableName, k -> new ArrayList<>()).add(columnName);
        }
        
        // Update columns with primary key information
        for (Map.Entry<String, List<String>> entry : primaryKeysByTable.entrySet()) {
            String tableName = entry.getKey();
            List<String> primaryKeys = entry.getValue();
            TableInfo tableInfo = tables.get(tableName);
            
            if (tableInfo != null) {
                for (ColumnInfo column : tableInfo.getColumns()) {
                    column.setPrimaryKey(primaryKeys.contains(column.getName()));
                }
            }
        }
    }

    /**
     * Processes foreign key data from bulk query results and populates TableInfo objects.
     */
    private void processForeignKeysFromBulkResult(List<Map<String, Object>> foreignKeyResults, 
                                                 Map<String, TableInfo> tables) {
        for (Map<String, Object> fkData : foreignKeyResults) {
            String tableName = (String) fkData.get("table_name");
            TableInfo tableInfo = tables.get(tableName);
            
            if (tableInfo != null) {
                ForeignKeyInfo fkInfo = new ForeignKeyInfo();
                fkInfo.setColumnName((String) fkData.get("column_name"));
                fkInfo.setReferencedTable((String) fkData.get("foreign_table_name"));
                fkInfo.setReferencedColumn((String) fkData.get("foreign_column_name"));
                tableInfo.getForeignKeys().add(fkInfo);
            }
        }
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
                enumInfo.setName((String) result.get(DatabaseColumnConstant.ENUM_NAME));
                enumInfo.setSchema((String) result.get(DatabaseColumnConstant.SCHEMA_NAME));

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

    @Override
    public Map<String, String> getDomainTypeToBaseTypeMap() {
        return domainTypeToBaseTypeCache.computeIfAbsent(allowedSchema, schema -> {
            Map<String, String> domainTypeToBaseTypeMap = new HashMap<>();

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_DOMAIN_TYPES,
                    schema
            );

            for (Map<String, Object> result : results) {
                String domainName = (String) result.get("domain_name");
                String baseType = (String) result.get("base_type");
                domainTypeToBaseTypeMap.put(domainName, baseType);
            }

            return domainTypeToBaseTypeMap;
        });
    }

    @Override
    public Map<String, String> getDomainTypeToBaseTypeMap(String schema) {
        return domainTypeToBaseTypeCache.computeIfAbsent(schema, schemaKey -> {
            Map<String, String> domainTypeToBaseTypeMap = new HashMap<>();

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    PostgresSqlConstant.GET_DOMAIN_TYPES,
                    schemaKey
            );

            for (Map<String, Object> result : results) {
                String domainName = (String) result.get("domain_name");
                String baseType = (String) result.get("base_type");
                domainTypeToBaseTypeMap.put(domainName, baseType);
            }

            return domainTypeToBaseTypeMap;
        });
    }



    @Override
    public void clearCache() {
        schemaCache.clear();
        enumCache.clear();
        compositeCache.clear();
        domainTypeToBaseTypeCache.clear();
    }

    @Override
    public void clearCache(String schema) {
        schemaCache.remove(schema);
        enumCache.remove(schema);
        compositeCache.remove(schema);
        domainTypeToBaseTypeCache.remove(schema);
    }

    /**
     * Gets cache statistics for monitoring purposes.
     *
     * @return cache statistics as a string
     */
    public String getCacheStats() {
        return "Schema: " + schemaCache.getStats() +
                ", Enum: " + enumCache.getStats() +
                ", Composite: " + compositeCache.getStats() +
                ", Domain: " + domainTypeToBaseTypeCache.getStats();
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
        domainTypeToBaseTypeCache.shutdown();
    }
}
