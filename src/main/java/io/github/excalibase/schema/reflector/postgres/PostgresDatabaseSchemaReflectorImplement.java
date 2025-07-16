package io.github.excalibase.schema.reflector.postgres;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.constant.SqlConstant;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExcalibaseService(
        serviceName = SupportedDatabaseConstant.POSTGRES
)
public class PostgresDatabaseSchemaReflectorImplement implements IDatabaseSchemaReflector {
    private final JdbcTemplate jdbcTemplate;
    private final TTLCache<String, Map<String, TableInfo>> schemaCache;

    @Value("${app.allowed-schema}")
    private String allowedSchema;
    
    @Value("${app.cache.schema-ttl-minutes:30}")
    private int schemaTtlMinutes;

    public PostgresDatabaseSchemaReflectorImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // Note: schemaTtlMinutes will be injected after construction, so we use default here
        // In a future enhancement, we could use constructor injection or @PostConstruct
        this.schemaCache = new TTLCache<>(Duration.ofMinutes(30)); // Default 30 minutes TTL
    }

    @Override
    public Map<String, TableInfo> reflectSchema() {
        // Check if not exists in cache => query
        return schemaCache.computeIfAbsent(allowedSchema, schema -> {
            Map<String, TableInfo> tables = new HashMap<>();

            List<String> tableNames = jdbcTemplate.queryForList(
                    SqlConstant.GET_TABLE_NAME,
                    String.class,
                    schema
            );

            for (String tableName : tableNames) {
                TableInfo tableInfo = new TableInfo();
                tableInfo.setName(tableName);

                // Get columns using pg_catalog
                List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                        SqlConstant.GET_COLUMNS,
                        tableName, schema
                );

                for (Map<String, Object> column : columns) {
                    ColumnInfo columnInfo = new ColumnInfo();
                    columnInfo.setName((String) column.get("column_name"));
                    columnInfo.setType((String) column.get("data_type"));
                    columnInfo.setNullable("YES".equals(column.get("is_nullable")));
                    tableInfo.getColumns().add(columnInfo);
                }

                // Get primary keys using pg_catalog
                List<String> primaryKeys = jdbcTemplate.queryForList(
                        SqlConstant.GET_PRIMARY_KEYS,
                        String.class,
                        tableName, schema
                );

                for (ColumnInfo column : tableInfo.getColumns()) {
                    column.setPrimaryKey(primaryKeys.contains(column.getName()));
                }

                // Get foreign keys using pg_catalog
                List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
                        SqlConstant.GET_FOREIGN_KEYS,
                        tableName, schema
                );

                for (Map<String, Object> fk : foreignKeys) {
                    ForeignKeyInfo fkInfo = new ForeignKeyInfo();
                    fkInfo.setColumnName((String) fk.get("column_name"));
                    fkInfo.setReferencedTable((String) fk.get("foreign_table_name"));
                    fkInfo.setReferencedColumn((String) fk.get("foreign_column_name"));
                    tableInfo.getForeignKeys().add(fkInfo);
                }

                tables.put(tableName, tableInfo);
            }

            return tables;
        });
    }

    @Override
    public void clearCache() {
        schemaCache.clear();
    }

    @Override
    public void clearCache(String schema) {
        schemaCache.remove(schema);
    }
    
    /**
     * Gets cache statistics for monitoring purposes.
     * 
     * @return cache statistics as a string
     */
    public String getCacheStats() {
        return schemaCache.getStats();
    }
    
    /**
     * Cleanup method called when the bean is destroyed.
     * Ensures proper shutdown of the cache's background threads.
     */
    @PreDestroy
    public void destroy() {
        schemaCache.shutdown();
    }
}
