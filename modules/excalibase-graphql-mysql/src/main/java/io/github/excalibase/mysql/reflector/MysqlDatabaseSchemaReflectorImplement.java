package io.github.excalibase.mysql.reflector;

import io.github.excalibase.annotation.ExcalibaseService;
import io.github.excalibase.config.AppConfig;
import io.github.excalibase.constant.SupportedDatabaseConstant;
import org.springframework.beans.factory.annotation.Autowired;
import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ComputedFieldFunction;
import io.github.excalibase.model.CustomCompositeTypeInfo;
import io.github.excalibase.model.CustomEnumInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.StoredProcedureInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.mysql.constant.MysqlSqlConstant;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL implementation of {@link IDatabaseSchemaReflector}.
 *
 * <p>Uses {@code INFORMATION_SCHEMA} queries to reflect table structure, columns,
 * primary keys, and foreign keys from MySQL.</p>
 *
 * <p>MySQL-specific notes:</p>
 * <ul>
 *   <li>No schema-level composite types (always returns empty list)</li>
 *   <li>No domain types (always returns empty map)</li>
 *   <li>ENUM columns are inline — schema-level enum discovery returns empty list</li>
 *   <li>No computed field discovery (always returns empty map)</li>
 * </ul>
 */
@ExcalibaseService(serviceName = SupportedDatabaseConstant.MYSQL)
public class MysqlDatabaseSchemaReflectorImplement implements IDatabaseSchemaReflector {

    private final JdbcTemplate jdbcTemplate;
    private final String schema;

    // Simple in-memory cache — evicted on clearCache()
    private final Map<String, Map<String, TableInfo>> schemaCache = new ConcurrentHashMap<>();

    /** Spring-managed constructor — schema resolved from {@link AppConfig#getAllowedSchema()}. */
    @Autowired
    public MysqlDatabaseSchemaReflectorImplement(JdbcTemplate jdbcTemplate, AppConfig appConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = appConfig.getAllowedSchema();
    }

    /** Test-friendly constructor — allows direct schema injection without a full AppConfig. */
    public MysqlDatabaseSchemaReflectorImplement(JdbcTemplate jdbcTemplate, String schema) {
        this.jdbcTemplate = jdbcTemplate;
        this.schema = schema;
    }

    @Override
    public Map<String, TableInfo> reflectSchema() {
        return schemaCache.computeIfAbsent(schema, this::doReflect);
    }

    private Map<String, TableInfo> doReflect(String schemaName) {
        Map<String, TableInfo> tables = new HashMap<>();

        // 1. Discover tables and views
        List<Map<String, Object>> tableRows = jdbcTemplate.queryForList(
                MysqlSqlConstant.GET_TABLE_NAMES, schemaName);

        for (Map<String, Object> row : tableRows) {
            String name = (String) row.get("TABLE_NAME");
            String tableType = (String) row.get("TABLE_TYPE");
            TableInfo info = new TableInfo();
            info.setName(name);
            info.setView("VIEW".equalsIgnoreCase(tableType));
            info.setColumns(new ArrayList<>());
            info.setForeignKeys(new ArrayList<>());
            tables.put(name, info);
        }

        if (tables.isEmpty()) return tables;

        // 2. Bulk-fetch columns
        List<Map<String, Object>> columnRows = jdbcTemplate.queryForList(
                MysqlSqlConstant.GET_COLUMNS, schemaName);
        for (Map<String, Object> row : columnRows) {
            String tableName = (String) row.get("TABLE_NAME");
            TableInfo tableInfo = tables.get(tableName);
            if (tableInfo == null) continue;

            ColumnInfo col = new ColumnInfo();
            col.setName((String) row.get("COLUMN_NAME"));
            col.setType(((String) row.get("DATA_TYPE")).toLowerCase());
            col.setNullable("YES".equalsIgnoreCase((String) row.get("IS_NULLABLE")));
            tableInfo.getColumns().add(col);
        }

        // 3. Bulk-fetch primary keys
        Map<String, Set<String>> pksByTable = new HashMap<>();
        List<Map<String, Object>> pkRows = jdbcTemplate.queryForList(
                MysqlSqlConstant.GET_PRIMARY_KEYS, schemaName);
        for (Map<String, Object> row : pkRows) {
            String tableName = (String) row.get("TABLE_NAME");
            String colName = (String) row.get("COLUMN_NAME");
            pksByTable.computeIfAbsent(tableName, k -> new HashSet<>()).add(colName);
        }
        for (Map.Entry<String, Set<String>> e : pksByTable.entrySet()) {
            TableInfo tableInfo = tables.get(e.getKey());
            if (tableInfo == null) continue;
            for (ColumnInfo col : tableInfo.getColumns()) {
                col.setPrimaryKey(e.getValue().contains(col.getName()));
            }
        }

        // 4. Bulk-fetch foreign keys
        List<Map<String, Object>> fkRows = jdbcTemplate.queryForList(
                MysqlSqlConstant.GET_FOREIGN_KEYS, schemaName);
        for (Map<String, Object> row : fkRows) {
            String tableName = (String) row.get("TABLE_NAME");
            TableInfo tableInfo = tables.get(tableName);
            if (tableInfo == null) continue;

            ForeignKeyInfo fk = new ForeignKeyInfo();
            fk.setColumnName((String) row.get("COLUMN_NAME"));
            fk.setReferencedTable((String) row.get("REFERENCED_TABLE_NAME"));
            fk.setReferencedColumn((String) row.get("REFERENCED_COLUMN_NAME"));
            tableInfo.getForeignKeys().add(fk);
        }

        return tables;
    }

    @Override
    public List<CustomEnumInfo> getCustomEnumTypes() {
        return List.of();
    }

    @Override
    public List<CustomEnumInfo> getCustomEnumTypes(String schema) {
        return List.of();
    }

    @Override
    public List<CustomCompositeTypeInfo> getCustomCompositeTypes() {
        return List.of();
    }

    @Override
    public List<CustomCompositeTypeInfo> getCustomCompositeTypes(String schema) {
        return List.of();
    }

    @Override
    public Map<String, String> getDomainTypeToBaseTypeMap() {
        return Map.of();
    }

    @Override
    public Map<String, String> getDomainTypeToBaseTypeMap(String schema) {
        return Map.of();
    }

    @Override
    public List<String> getEnumValues(String enumName, String schema) {
        return List.of();
    }

    @Override
    public void clearCache() {
        schemaCache.clear();
    }

    @Override
    public void clearCache(String schema) {
        schemaCache.remove(schema);
    }

    @Override
    public Map<String, List<ComputedFieldFunction>> discoverComputedFields() {
        return Map.of();
    }

    @Override
    public Map<String, List<ComputedFieldFunction>> discoverComputedFields(String schema) {
        return Map.of();
    }

    @Override
    public List<StoredProcedureInfo> discoverStoredProcedures() {
        return discoverStoredProcedures(schema);
    }

    @Override
    public List<StoredProcedureInfo> discoverStoredProcedures(String schemaName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                MysqlSqlConstant.GET_PROCEDURES, schemaName);

        List<StoredProcedureInfo> procedures = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String procName = (String) row.get("ROUTINE_NAME");

            List<Map<String, Object>> paramRows = jdbcTemplate.queryForList(
                    MysqlSqlConstant.GET_PROCEDURE_PARAMS, schemaName, procName);

            List<StoredProcedureInfo.ProcedureParam> params = new ArrayList<>();
            for (Map<String, Object> p : paramRows) {
                String mode = (String) p.get("PARAMETER_MODE");
                if (mode == null) continue; // skip return value row
                params.add(new StoredProcedureInfo.ProcedureParam(
                        (String) p.get("PARAMETER_NAME"),
                        (String) p.get("DATA_TYPE"),
                        mode,
                        ((Number) p.get("ORDINAL_POSITION")).intValue()
                ));
            }
            procedures.add(new StoredProcedureInfo(procName, schemaName, params));
        }
        return procedures;
    }
}
