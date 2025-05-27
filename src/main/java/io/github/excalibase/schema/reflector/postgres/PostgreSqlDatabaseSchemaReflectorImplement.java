package io.github.excalibase.schema.reflector.postgres;

import io.github.excalibase.model.ColumnInfo;
import io.github.excalibase.model.ForeignKeyInfo;
import io.github.excalibase.model.TableInfo;
import io.github.excalibase.schema.reflector.IDatabaseSchemaReflector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostgreSqlDatabaseSchemaReflectorImplement implements IDatabaseSchemaReflector {
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.allowedSchema}")
    private String allowedSchema;

    public PostgreSqlDatabaseSchemaReflectorImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, TableInfo> reflectSchema() {
        Map<String, TableInfo> tables = new HashMap<>();

        String schema = allowedSchema;
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
    }
}
