package io.github.excalibase.schema;

import io.github.excalibase.cache.TTLCache;
import io.github.excalibase.cdc.NatsCDCService;
import io.github.excalibase.compiler.SqlCompiler;
import io.github.excalibase.config.datasource.DynamicDataSourceManager;
import io.github.excalibase.security.JwtClaims;
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

import javax.sql.DataSource;
import java.time.Duration;
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
    private final DynamicDataSourceManager dataSourceManager;

    public record EngineState(SqlCompiler compiler, IntrospectionHandler introspectionHandler,
                              MutationExecutor mutationExecutor) {}

    private volatile EngineState engineState;
    private final TTLCache<String, EngineState> tenantEngineStates;

    public GraphqlSchemaManager(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate txTemplate,
            @Value("${app.schemas:public}") String dbSchema,
            @Value("${app.max-rows:30}") int maxRows,
            @Value("${app.database-type:postgres}") String databaseType,
            @Value("${app.max-query-depth:0}") int maxQueryDepth,
            @Value("${app.cache.schema-ttl-minutes:30}") int schemaTtlMinutes,
            @Autowired(required = false) NatsCDCService natsCDCService,
            @Autowired(required = false) DynamicDataSourceManager dataSourceManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = txTemplate;
        this.dbSchema = dbSchema;
        this.maxRows = maxRows;
        this.databaseType = databaseType;
        this.maxQueryDepth = maxQueryDepth;
        this.dataSourceManager = dataSourceManager;
        this.tenantEngineStates = new TTLCache<>(Duration.ofMinutes(schemaTtlMinutes));
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

    /**
     * Returns the default EngineState (for non-JWT requests using static datasource).
     * In multi-tenant-only mode (no spring.datasource.url), this throws a clear error
     * instead of returning an empty schema.
     */
    public EngineState getEngineState() {
        return engineState;
    }

    /**
     * Resolve EngineState based on JWT claims.
     * If claims have orgSlug/projectName → tenant-specific state.
     * Otherwise → default state.
     */
    public EngineState resolveEngineState(JwtClaims claims) {
        if (claims != null && claims.orgSlug() != null && claims.projectName() != null) {
            return getEngineState(claims.orgSlug(), claims.projectName());
        }
        return engineState;
    }

    /**
     * Get or build EngineState for a specific tenant.
     * Introspects the tenant's database on first access, then caches.
     */
    public EngineState getEngineState(String orgSlug, String projectName) {
        String tenantKey = orgSlug + "/" + projectName;
        return tenantEngineStates.computeIfAbsent(tenantKey,
            key -> buildTenantEngineState(orgSlug, projectName));
    }

    public String getDatabaseType() {
        return databaseType;
    }

    /** Reinitialize schema and compiler. Called on DDL events from NatsCDCService. */
    public void reload() {
        EngineState previous = engineState;
        try {
            init();
            log.info("Schema reloaded");
        } catch (Exception e) {
            engineState = previous;
            log.error("Schema reload failed — retaining previous state", e);
        }
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
        loadMultiSchema(schemaInfo, schemaList, loader, jdbcTemplate);
    }

    private String findCompoundKey(SchemaInfo schemaInfo, String rawTable, List<String> allSchemas) {
        for (String s : allSchemas) {
            String candidate = s + "." + rawTable;
            if (schemaInfo.hasTable(candidate)) return candidate;
        }
        return null;
    }

    private EngineState buildTenantEngineState(String orgSlug, String projectName) {
        if (dataSourceManager == null) {
            throw new IllegalStateException("Multi-tenant not enabled — DynamicDataSourceManager is null");
        }
        DataSource tenantDs = dataSourceManager.getDataSource(orgSlug, projectName);
        JdbcTemplate tenantJdbc = new JdbcTemplate(tenantDs);

        SqlEngine engine = SqlEngineFactory.create(databaseType);
        List<String> schemaList = resolveSchemaList();

        SchemaInfo schemaInfo = new SchemaInfo();
        loadMultiSchema(schemaInfo, schemaList, engine.schemaLoader(), tenantJdbc);

        String defaultSchema = schemaList.isEmpty() ? "public" : schemaList.getFirst();
        SqlCompiler compiler = new SqlCompiler(schemaInfo, defaultSchema, maxRows,
                engine.dialect(), engine.mutationCompiler(), maxQueryDepth);

        IntrospectionHandler handler = null;
        try {
            handler = new IntrospectionHandler(schemaInfo);
        } catch (Exception e) {
            log.warn("IntrospectionHandler failed for tenant {}/{}", orgSlug, projectName, e);
        }

        TransactionTemplate tenantTx = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(tenantDs));
        MutationExecutor mutationExecutor = SqlEngineFactory.createMutationExecutor(
                databaseType, tenantJdbc, tenantTx);

        log.info("built_tenant_engine tenant={}/{} tables={}",
                orgSlug, projectName, schemaInfo.getTableNames().size());
        return new EngineState(compiler, handler, mutationExecutor);
    }

    private void loadMultiSchema(SchemaInfo schemaInfo, List<String> schemaList,
                                 SchemaLoader loader, JdbcTemplate jdbc) {
        Map<String, SchemaInfo> perSchema = new LinkedHashMap<>();
        loader.loadAll(jdbc, schemaList, perSchema);

        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            mergeTablesAndColumns(schemaInfo, schema, temp);
            mergeEnumsProcsAndComposites(schemaInfo, schema, temp);
        }
        mergeForeignKeys(schemaInfo, perSchema, schemaList);
    }

    private void mergeTablesAndColumns(SchemaInfo target, String schema, SchemaInfo source) {
        for (String table : source.getTableNames()) {
            String key = schema + "." + table;
            for (String col : source.getColumns(table)) {
                target.addColumn(key, col, source.getColumnType(table, col));
                String enumType = source.getEnumType(table, col);
                if (enumType != null) {
                    target.addColumnEnumType(key, col, schema + "." + enumType);
                }
            }
            target.setTableSchema(key, schema);
            if (source.hasPrimaryKey(table)) {
                for (String pk : source.getPrimaryKeys(table)) {
                    target.addPrimaryKey(key, pk);
                }
            }
            if (source.isView(table)) {
                target.addView(key);
            }
            var computed = source.getComputedFields(table);
            if (computed != null) {
                for (var cf : computed) {
                    target.addComputedField(key, cf.functionName(), cf.returnType());
                }
            }
        }
    }

    private void mergeEnumsProcsAndComposites(SchemaInfo target, String schema, SchemaInfo source) {
        for (var entry : source.getEnumTypes().entrySet()) {
            for (String label : entry.getValue()) {
                target.addEnumValue(schema + "." + entry.getKey(), label);
            }
        }
        for (var entry : source.getStoredProcedures().entrySet()) {
            target.addStoredProcedure(schema + "." + entry.getKey(), entry.getValue());
        }
        for (var entry : source.getCompositeTypes().entrySet()) {
            for (var field : entry.getValue()) {
                target.addCompositeTypeField(schema + "." + entry.getKey(), field.name(), field.type());
            }
        }
    }

    private void mergeForeignKeys(SchemaInfo target, Map<String, SchemaInfo> perSchema, List<String> schemaList) {
        for (var schemaEntry : perSchema.entrySet()) {
            String schema = schemaEntry.getKey();
            SchemaInfo temp = schemaEntry.getValue();
            for (var entry : temp.getAllForwardFks().entrySet()) {
                String fkKey = entry.getKey();
                String fromTable = fkKey.substring(0, fkKey.lastIndexOf('.'));
                SchemaInfo.FkInfo fk = entry.getValue();
                String compoundFrom = schema + "." + fromTable;
                String compoundTo = findCompoundKey(target, fk.refTable(), schemaList);
                if (compoundTo != null && target.hasTable(compoundFrom)) {
                    if (fk.isComposite()) {
                        target.addCompositeForeignKey(compoundFrom, fk.fkColumns(), compoundTo, fk.refColumns());
                    } else {
                        target.addForeignKey(compoundFrom, fk.fkColumn(), compoundTo, fk.refColumn());
                    }
                }
            }
        }
    }
}
