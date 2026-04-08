package io.github.excalibase.schema;

import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.spi.MutationExecutor;
import io.github.excalibase.spi.SchemaLoader;
import io.github.excalibase.spi.SqlEngine;
import io.github.excalibase.spi.SqlEngineFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages database schema introspection, compiler creation, and hot reloads.
 * Produces an immutable {@link EngineState} that the controller snapshots per-request.
 */
@Component
public class GraphqlSchemaManager {

    private static final Logger log = LoggerFactory.getLogger(GraphqlSchemaManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;
    private final String dbSchema;
    private final int maxRows;
    private final String databaseType;
    private final int maxQueryDepth;

    public record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                              MutationExecutor mutationExecutor) {}

    private volatile EngineState engineState;

    public GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate txTemplate,
            @Value("${app.schemas:public}") String dbSchema,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:0}") int maxQueryDepth,
            @Autowired(required = false) NatsCDCService natsCDCService) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.dbSchema = dbSchema;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        if (natsCDCService != null) {
            natsCDCService.setSchemaReloadCallback(this::reload);
        }
    }

    @PostConstruct
    public void init() {
        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> schemaList = resolveSchemaList();

        SchemaInfo schemaInfo = new SchemaInfo();
        try {
            loadMultiSchema(schemaInfo, schemaList, engine.schemaLoader());
        } catch (Exception e) {
            log.warn("Failed to load database schema — starting with empty schema", e);
        }

        String defaultSchema = schemaList.isEmpty() ? "public" : schemaList.getFirst();
        SqlCompiler newCompiler = new SqlCompiler(schemaInfo, defaultSchema, maxRows,
                engine.dialect(), engine.mutationCompiler(), maxQueryDepth);

        IntrospectionHandler newHandler = null;
        try {
            newHandler = new IntrospectionHandler(schemaInfo);
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed to build schema", e);
        }

        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, jdbcTemplate, txTemplate);

        engineState = new EngineState(newCompiler, newHandler, mutationExecutor);
    }

    public EngineState getEngineState() {
        return engineState;
    }

    public String getDatabaseType() {
        return databaseType;
    }

    /** Reinitialize schema and compiler. Called on DDL events from NatsCDCService. */
    public void reload() {
        init();
        log.info("Schema reloaded");
    }

    private List<String> resolveSchemaList() {
        if ("ALL".equalsIgnoreCase(dbSchema.trim())) {
            return discoverSchemas();
        }
        return Arrays.stream(dbSchema.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> discoverSchemas() {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT schema_name FROM information_schema.schemata " +
                    "WHERE schema_name NOT IN ('pg_catalog', 'information_schema') " +
                    "AND schema_name NOT LIKE 'pg_toast%' " +
                    "AND schema_name NOT LIKE 'pg_temp_%' " +
                    "ORDER BY schema_name",
                    String.class);
        } catch (Exception e) {
            log.warn("Failed to discover schemas", e);
            return List.of();
        }
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList, SchemaLoader loader) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbcTemplate, schemaList, perSchema);

        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();

            for (String table : temp.getTableNames()) {
                String key = schema + "." + table;
                for (String col : temp.getColumns(table)) {
                    schemaInfo.addColumn(key, col, temp.getColumnType(table, col));
                    String enumType = temp.getEnumType(table, col);
                    if (enumType != null) {
                        schemaInfo.addColumnEnumType(key, col, schema + "." + enumType);
                    }
                }
                schemaInfo.setTableSchema(key, schema);
                if (temp.hasPrimaryKey(table)) {
                    for (String pk : temp.getPrimaryKeys(table)) {
                        schemaInfo.addPrimaryKey(key, pk);
                    }
                }
                if (temp.isView(table)) {
                    schemaInfo.addView(key);
                }
                var computed = temp.getComputedFields(table);
                if (computed != null) {
                    for (var cf : computed) {
                        schemaInfo.addComputedField(key, cf.functionName(), cf.returnType());
                    }
                }
            }
            for (var entry : temp.getEnumTypes().entrySet()) {
                for (String label : entry.getValue()) {
                    schemaInfo.addEnumValue(schema + "." + entry.getKey(), label);
                }
            }
            for (var entry : temp.getStoredProcedures().entrySet()) {
                schemaInfo.addStoredProcedure(schema + "." + entry.getKey(), entry.getValue());
            }
            for (var entry : temp.getCompositeTypes().entrySet()) {
                for (var field : entry.getValue()) {
                    schemaInfo.addCompositeTypeField(schema + "." + entry.getKey(), field.name(), field.type());
                }
            }
        }

        // FK pass — translate raw table refs to compound keys
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            for (var entry : temp.getAllForwardFks().entrySet()) {
                String fkKey = entry.getKey();
                String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
                SchemaInfo.FkInfo fk = entry.getValue();
                String compoundFrom = schema + "." + fromTable;
                String compoundTo = findCompoundKey(schemaInfo, fk.refTable(), schemaList);
                if (compoundTo != null && schemaInfo.hasTable(compoundFrom)) {
                    if (fk.isComposite()) {
                        schemaInfo.addCompositeForeignKey(compoundFrom, fk.fkColumns(), compoundTo, fk.refColumns());
                    } else {
                        schemaInfo.addForeignKey(compoundFrom, fk.fkColumn(), compoundTo, fk.refColumn());
                    }
                }
            }
        }
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas) {
        for (String s : allSchemas) {
            String candidate = s + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }
}
